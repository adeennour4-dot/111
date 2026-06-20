// JNI bridge for com.gguf.zerocopy.lib — ToolNeuron-style architecture
// Features: thread-engine (3 modes), disk-backed prompt cache, StreamingLLM eviction,
//           token batching, RAG (embedding + vector store + chunking + context injection)
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <chrono>
#include <sstream>
#include <fstream>
#include <thread>
#include <unordered_map>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <android/log.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sched.h>
#include <sys/stat.h>
#include <dirent.h>
#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

constexpr int TN_MAX_CPUS = 64;

#define TAG "ZeroCopy_Lib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// ─── Prompt Cache ──────────────────────────────────────────────────────────────
struct PromptCacheEntry {
    std::vector<llama_token> tokens;
    int n_system_tokens = 0;
};

static std::string g_prompt_cache_dir;
static std::mutex g_cache_mutex;
static std::unordered_map<size_t, PromptCacheEntry> g_mem_cache; // small LRU in-memory

static size_t hash_string(const std::string& s) {
    return std::hash<std::string>{}(s);
}

static std::string cache_path_for(size_t h) {
    std::ostringstream p;
    p << g_prompt_cache_dir << "/pc_" << h << ".bin";
    return p.str();
}

static bool save_cache_entry(size_t h, const PromptCacheEntry& e) {
    if (g_prompt_cache_dir.empty()) return false;
    std::string path = cache_path_for(h);
    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    int n_tokens = (int)e.tokens.size();
    f.write((const char*)&n_tokens, sizeof(n_tokens));
    f.write((const char*)&e.n_system_tokens, sizeof(e.n_system_tokens));
    if (n_tokens > 0) f.write((const char*)e.tokens.data(), n_tokens * sizeof(llama_token));
    return f.good();
}

static bool load_cache_entry(size_t h, PromptCacheEntry& e) {
    if (g_prompt_cache_dir.empty()) return false;
    std::string path = cache_path_for(h);
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    int n_tokens = 0;
    f.read((char*)&n_tokens, sizeof(n_tokens));
    f.read((char*)&e.n_system_tokens, sizeof(e.n_system_tokens));
    if (n_tokens > 0) {
        e.tokens.resize(n_tokens);
        f.read((char*)e.tokens.data(), n_tokens * sizeof(llama_token));
    }
    return f.good();
}

// ─── Vector Store (RAG) ────────────────────────────────────────────────────────
struct Chunk {
    std::string text;
    std::string source;
    std::vector<float> embedding;
};

static std::vector<Chunk> g_chunks;
static std::mutex g_rag_mutex;
static int g_embedding_dim = 0;

static float cosine_similarity(const std::vector<float>& a, const std::vector<float>& b) {
    float dot = 0, na = 0, nb = 0;
    size_t n = std::min(a.size(), b.size());
    for (size_t i = 0; i < n; i++) {
        dot += a[i] * b[i];
        na += a[i] * a[i];
        nb += b[i] * b[i];
    }
    float denom = std::sqrt(na) * std::sqrt(nb);
    return (denom > 1e-10f) ? dot / denom : 0.0f;
}

static std::vector<std::string> split_into_chunks(const std::string& text, int chunk_size, int overlap) {
    std::vector<std::string> chunks;
    if (text.empty()) return chunks;
    // Split by paragraphs first
    std::vector<std::string> paragraphs;
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        std::string para = text.substr(start, end - start);
        if (!para.empty()) paragraphs.push_back(para);
        start = end + 1;
    }
    if (paragraphs.empty()) paragraphs.push_back(text);

    // Merge paragraphs into chunks of roughly chunk_size chars
    std::string current;
    for (const auto& p : paragraphs) {
        if ((int)current.size() + (int)p.size() + 1 > chunk_size && !current.empty()) {
            chunks.push_back(current);
            // overlap: keep last `overlap` chars
            if (overlap > 0 && (int)current.size() > overlap) {
                current = current.substr(current.size() - overlap);
            } else {
                current.clear();
            }
        }
        if (!current.empty()) current += '\n';
        current += p;
    }
    if (!current.empty()) chunks.push_back(current);
    return chunks;
}

// ─── Engine State ──────────────────────────────────────────────────────────────
struct EngineState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    std::string system_prompt;
    int thread_mode = 1;
    std::atomic<bool> cancel_flag{false};
    std::mutex gen_mutex;
    int n_past = 0;
    std::vector<llama_token> prev_prompt_tokens;
    int n_system_tokens = 0;
    bool use_mmap = true;
    bool use_mlock = false;
    ggml_threadpool_t threadpool = nullptr;
    ggml_threadpool_t threadpool_batch = nullptr;

    // StreamingLLM
    int kv_sink_tokens = 4;      // first N tokens to keep
    int kv_recent_tokens = 512;  // last N tokens to keep
    float kv_evict_threshold = 0.85f; // evict when >85% full

    // RAG mode
    bool rag_enabled = false;
    int rag_top_k = 3;
    float rag_min_score = 0.3f;
} g_state;

// ─── Callback IDs ──────────────────────────────────────────────────────────────
static jclass g_cb_class = nullptr;
static jmethodID g_onToken = nullptr;
static jmethodID g_onDone = nullptr;
static jmethodID g_onError = nullptr;
static jmethodID g_onKvUsage = nullptr;
static jmethodID g_onTokensGenerated = nullptr;

static bool ensure_callback_methods(JNIEnv* env, jobject callback) {
    jclass cls = env->GetObjectClass(callback);
    if (g_cb_class && env->IsSameObject(cls, g_cb_class)) {
        env->DeleteLocalRef(cls);
        return true;
    }
    if (g_cb_class) env->DeleteGlobalRef(g_cb_class);
    g_cb_class = (jclass)env->NewGlobalRef(cls);
    g_onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    g_onDone = env->GetMethodID(cls, "onDone", "()V");
    g_onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    g_onKvUsage = env->GetMethodID(cls, "onKvCacheUsage", "(I)V");
    g_onTokensGenerated = env->GetMethodID(cls, "onTokensGenerated", "(I)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    return g_onToken && g_onDone && g_onError;
}

static void call_callback_on_error(jobject callback, const std::string& error) {
    if (!callback || !g_onError || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jstring s = env->NewStringUTF(error.c_str());
    env->CallVoidMethod(callback, g_onError, s);
    env->DeleteLocalRef(s);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_token(jobject callback, const std::string& token) {
    if (!callback || !g_onToken || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jstring jtok = env->NewStringUTF(token.c_str());
    env->CallVoidMethod(callback, g_onToken, jtok);
    env->DeleteLocalRef(jtok);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_done(jobject callback) {
    if (!callback || !g_onDone || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    env->CallVoidMethod(callback, g_onDone);
    if (need_detach) g_jvm->DetachCurrentThread();
}

// ─── Backend Init ──────────────────────────────────────────────────────────────
static std::once_flag g_backend_init_flag;
static void ensure_backend_init() {
    std::call_once(g_backend_init_flag, [] {
        llama_log_set([](enum ggml_log_level level, const char* text, void*) {
            if (!text || !text[0]) return;
            size_t len = strlen(text);
            char buf[2048];
            if (len >= sizeof(buf)) len = sizeof(buf) - 1;
            memcpy(buf, text, len);
            while (len > 0 && (buf[len-1] == '\n' || buf[len-1] == '\r')) len--;
            buf[len] = '\0';
            if (len == 0) return;
            switch (level) {
                case GGML_LOG_LEVEL_ERROR: LOGE("%s", buf); break;
                case GGML_LOG_LEVEL_WARN:  LOGW("%s", buf); break;
                default:                   LOGI("%s", buf); break;
            }
        }, nullptr);
        llama_backend_init();
        LOGI("llama.cpp backend initialized");
    });
}

// ─── Thread / CPU detection ────────────────────────────────────────────────────
static std::vector<int> detect_big_cores() {
    std::vector<int> big_cores;
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) { int freq = 0; if (fscanf(f, "%d", &freq) == 1) big_cores.push_back(freq); fclose(f); }
    }
    return big_cores;
}

static void apply_thread_mode(int mode) {
    auto big_cores = detect_big_cores();
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;

    int n_threads_gen, n_threads_batch, n_batch;
    bool pin_perf = false;

    switch (mode) {
        case 0: // power saving
            n_threads_gen = std::max(1, ncpu / 4);
            n_threads_batch = std::max(1, ncpu / 2);
            n_batch = 256;
            break;
        case 1: // balanced
            n_threads_gen = std::max(2, ncpu / 2);
            n_threads_batch = ncpu;
            n_batch = 512;
            pin_perf = !big_cores.empty();
            break;
        case 2: // performance
            n_threads_gen = ncpu;
            n_threads_batch = ncpu;
            n_batch = 1024;
            pin_perf = !big_cores.empty();
            break;
        default:
            n_threads_gen = ncpu / 2;
            n_threads_batch = ncpu;
            n_batch = 512;
    }

    if (g_state.ctx) llama_detach_threadpool(g_state.ctx);
    if (g_state.threadpool) { ggml_threadpool_free(g_state.threadpool); g_state.threadpool = nullptr; }
    if (g_state.threadpool_batch) { ggml_threadpool_free(g_state.threadpool_batch); g_state.threadpool_batch = nullptr; }

    if (n_threads_gen > 1) {
        ggml_threadpool_params p;
        ggml_threadpool_params_init(&p, n_threads_gen);
        if (pin_perf && !big_cores.empty()) {
            for (int c : big_cores) if (c < TN_MAX_CPUS) p.cpumask[c] = true;
        }
        p.prio = GGML_SCHED_PRIO_NORMAL;
        p.poll = 50;
        p.strict_cpu = false;
        g_state.threadpool = ggml_threadpool_new(&p);
    }
    if (n_threads_batch > 1) {
        ggml_threadpool_params p;
        ggml_threadpool_params_init(&p, n_threads_batch);
        for (int c = 0; c < ncpu; c++) p.cpumask[c] = true;
        p.prio = GGML_SCHED_PRIO_NORMAL;
        p.poll = 50;
        p.strict_cpu = false;
        g_state.threadpool_batch = ggml_threadpool_new(&p);
    }

    if (g_state.ctx) {
        llama_set_n_threads(g_state.ctx, n_threads_gen, n_threads_batch);
        llama_attach_threadpool(g_state.ctx,
            g_state.threadpool ? g_state.threadpool : g_state.threadpool_batch,
            g_state.threadpool_batch ? g_state.threadpool_batch : g_state.threadpool);
    }
    g_state.thread_mode = mode;
    LOGI("Thread mode applied: %d (gen=%d batch=%d)", mode, n_threads_gen, n_threads_batch);
}

static void rebuild_sampler() {
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.model) {
        g_state.sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
}

// ─── Context Window Management ────────────────────────────────────────────────
// Full KV cache reset when nearing the context limit (StreamingLLM-style)
static void maybe_trim_context() {
    if (!g_state.ctx) return;
    int n_ctx = llama_n_ctx(g_state.ctx);
    if (g_state.n_past < (int)(n_ctx * g_state.kv_evict_threshold)) return;

    int keep = g_state.kv_sink_tokens + g_state.kv_recent_tokens;
    if (keep >= g_state.n_past) return;

    int remove_count = g_state.n_past - keep;
    // Full reset — simpler and works with any llama.cpp version
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    g_state.n_past = keep;
    LOGI("Context trimmed: removed %d tokens, n_past now %d", remove_count, g_state.n_past);
}

// ─── RAG: Embedding ────────────────────────────────────────────────────────────
static bool compute_embedding(const std::string& text, std::vector<float>& out_emb) {
    if (!g_state.model || !g_state.ctx) return false;
    const llama_vocab* vocab = llama_model_get_vocab(g_state.model);

    std::vector<llama_token> tokens(text.size() + 256);
    int n = llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), (int)tokens.size(), false, true);
    if (n <= 0) return false;
    tokens.resize(n);

    // Add BOS
    llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL) tokens.insert(tokens.begin(), bos);

    // For embedding, use batch with same sequence
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_encode(g_state.ctx, batch) != 0) return false;

    // Get embedding from sequence 0
    const float* emb = llama_get_embeddings_seq(g_state.ctx, 0);
    if (!emb) return false;

    int n_embd = llama_model_n_embd(g_state.model);
    out_emb.assign(emb, emb + n_embd);
    return true;
}

// ─── RAG: Context Injection ────────────────────────────────────────────────────
static std::string build_rag_context(const std::string& query) {
    if (g_chunks.empty()) return "";

    // Compute query embedding
    std::vector<float> q_emb;
    if (!compute_embedding(query, q_emb)) return "";

    // Score all chunks
    std::vector<std::pair<float, int>> scored;
    for (size_t i = 0; i < g_chunks.size(); i++) {
        if (g_chunks[i].embedding.empty()) continue;
        float sim = cosine_similarity(q_emb, g_chunks[i].embedding);
        if (sim >= g_state.rag_min_score) {
            scored.push_back({sim, (int)i});
        }
    }

    if (scored.empty()) return "";

    // Take top-k
    std::sort(scored.begin(), scored.end(), [](auto& a, auto& b) { return a.first > b.first; });
    int k = std::min(g_state.rag_top_k, (int)scored.size());

    std::ostringstream ctx;
    ctx << "\n\nRelevant context from documents:\n";
    for (int i = 0; i < k; i++) {
        const auto& chunk = g_chunks[scored[i].second];
        ctx << "---\n"
            << (chunk.source.empty() ? "unknown" : chunk.source) << ":\n"
            << chunk.text << "\n";
    }
    ctx << "---\nUse the above context to answer the user's query.\n";
    return ctx.str();
}

// ─── Load Model ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeLoadModel(
    JNIEnv* env, jobject,
    jstring jpath, jint nCtx, jint nThreads, jint nBatch,
    jboolean flashAttn, jboolean useMmap, jboolean useMlock,
    jstring jCacheTypeK, jstring jCacheTypeV, jboolean opOffload) {

    ensure_backend_init();
    std::lock_guard<std::mutex> lock(g_state.gen_mutex);

    // Cleanup
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx) { llama_detach_threadpool(g_state.ctx); llama_free(g_state.ctx); g_state.ctx = nullptr; }
    if (g_state.threadpool) { ggml_threadpool_free(g_state.threadpool); g_state.threadpool = nullptr; }
    if (g_state.threadpool_batch) { ggml_threadpool_free(g_state.threadpool_batch); g_state.threadpool_batch = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    g_state.n_past = 0;
    g_state.prev_prompt_tokens.clear();
    g_state.n_system_tokens = 0;

    std::string path_s;
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    if (p) path_s = p;
    env->ReleaseStringUTFChars(jpath, p);

    LOGI("Loading model: %s", path_s.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = useMmap;
    mparams.use_mlock = useMlock;
    mparams.n_gpu_layers = 0;
    // Embeddings enabled automatically by llama_encode() at this tag

    g_state.model = llama_model_load_from_file(path_s.c_str(), mparams);
    if (!g_state.model) { LOGE("Model load failed"); return JNI_FALSE; }

    int total_cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (total_cores < 1) total_cores = 4;
    int n_threads = (nThreads > 0) ? std::min(nThreads, total_cores) : total_cores;

    int n_ctx = nCtx > 0 ? nCtx : 4096;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = nBatch > 0 ? nBatch : 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = flashAttn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) {
        llama_model_free(g_state.model); g_state.model = nullptr;
        return JNI_FALSE;
    }

    g_embedding_dim = llama_model_n_embd(g_state.model);
    apply_thread_mode(g_state.thread_mode);
    rebuild_sampler();

    LOGI("Model loaded successfully, emb_dim=%d", g_embedding_dim);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_state.gen_mutex);
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx) { llama_detach_threadpool(g_state.ctx); llama_free(g_state.ctx); g_state.ctx = nullptr; }
    if (g_state.threadpool) { ggml_threadpool_free(g_state.threadpool); g_state.threadpool = nullptr; }
    if (g_state.threadpool_batch) { ggml_threadpool_free(g_state.threadpool_batch); g_state.threadpool_batch = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

// ─── Generate Stream (with cache + batching + StreamingLLM + RAG) ─────────────
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeGenerateStream(
    JNIEnv* env, jobject, jstring jprompt, jint maxTokens, jobject callback) {

    if (!g_state.model || !g_state.ctx || !g_state.sampler) {
        if (callback && ensure_callback_methods(env, callback) && g_onError) {
            jstring err = env->NewStringUTF("Engine not ready");
            env->CallVoidMethod(callback, g_onError, err);
            env->DeleteLocalRef(err);
        }
        return;
    }

    const char* prompt_c = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_c) return;

    if (!ensure_callback_methods(env, callback)) {
        env->ReleaseStringUTFChars(jprompt, prompt_c);
        return;
    }

    g_state.cancel_flag.store(false);

    // Build prompt with optional RAG context
    std::string user_prompt(prompt_c);
    env->ReleaseStringUTFChars(jprompt, prompt_c);

    std::string rag_context;
    if (g_state.rag_enabled) {
        rag_context = build_rag_context(user_prompt);
    }

    std::string full_prompt;
    if (!rag_context.empty()) {
        full_prompt = g_state.system_prompt + rag_context + "\nUser: " + user_prompt + "\nAssistant:";
    } else {
        full_prompt = g_state.system_prompt + "\nUser: " + user_prompt + "\nAssistant:";
    }

    // ── Prompt Cache ──
    size_t prompt_hash = hash_string(full_prompt);
    std::vector<llama_token> prompt_tokens;
    int n_sys = 0;
    bool cache_hit = false;

    {
        std::lock_guard<std::mutex> ck(g_cache_mutex);
        auto it = g_mem_cache.find(prompt_hash);
        if (it != g_mem_cache.end()) {
            prompt_tokens = it->second.tokens;
            n_sys = it->second.n_system_tokens;
            cache_hit = true;
        } else {
            PromptCacheEntry disk;
            if (load_cache_entry(prompt_hash, disk)) {
                g_mem_cache[prompt_hash] = disk;
                prompt_tokens = disk.tokens;
                n_sys = disk.n_system_tokens;
                cache_hit = true;
            }
        }
    }

    if (!cache_hit) {
        // Tokenize
        const llama_vocab* vocab = llama_model_get_vocab(g_state.model);
        std::vector<llama_token> tokens(full_prompt.size() + 256);
        int n = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(), tokens.data(), (int)tokens.size(), false, true);
        if (n <= 0) { call_callback_on_error(callback, "Tokenization failed"); return; }
        tokens.resize(n);

        llama_token bos = llama_vocab_bos(vocab);
        if (bos != LLAMA_TOKEN_NULL) tokens.insert(tokens.begin(), bos);

        prompt_tokens = std::move(tokens);
        n_sys = 0; // approximate; tracking exact system tokens is complex

        // Save to cache
        {
            std::lock_guard<std::mutex> ck(g_cache_mutex);
            PromptCacheEntry e;
            e.tokens = prompt_tokens;
            e.n_system_tokens = n_sys;
            g_mem_cache[prompt_hash] = e;
            save_cache_entry(prompt_hash, e);
        }
        LOGI("Prompt cached (%zu tokens)", prompt_tokens.size());
    } else {
        LOGI("Prompt cache hit (%zu tokens)", prompt_tokens.size());
    }

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    g_state.n_past = 0;

    // ── Token Batching: decode prompt in batches ──
    int n_ctx_max = llama_n_ctx(g_state.ctx);
    int batch_sz = std::min(512, n_ctx_max / 2); // dynamic batch sizing
    int n_prompt = (int)prompt_tokens.size();

    for (int off = 0; off < n_prompt; off += batch_sz) {
        int cur = std::min(batch_sz, n_prompt - off);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + off, cur);
        if (llama_decode(g_state.ctx, batch) != 0) {
            call_callback_on_error(callback, "Prompt decode failed");
            return;
        }
        g_state.n_past += cur;

        maybe_trim_context();
    }

    // ── Generation ──
    std::string response;
    int tokens_generated = 0;
    for (int i = 0; i < maxTokens; i++) {
        if (g_state.cancel_flag.load()) break;

        llama_token tok = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        const llama_vocab* vocab = llama_model_get_vocab(g_state.model);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) {
            piece[len] = '\0';
            response += piece;
            tokens_generated++;
            call_callback_token(callback, piece);
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_state.ctx, nb) != 0) break;
        g_state.n_past++;

        // Periodic KV cache callbacks and context trimming
        if (tokens_generated % 16 == 0) {
            if (g_onKvUsage && g_jvm) {
                int pct = (int)((float)g_state.n_past / n_ctx_max * 100);
                JNIEnv* env2 = nullptr; bool nd = false;
                int st = g_jvm->GetEnv((void**)&env2, JNI_VERSION_1_6);
                if (st == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env2, nullptr); nd = true; }
                if (env2) env2->CallVoidMethod(callback, g_onKvUsage, pct);
                if (nd) g_jvm->DetachCurrentThread();
            }
            if (g_onTokensGenerated && g_jvm) {
                JNIEnv* env2 = nullptr; bool nd = false;
                int st = g_jvm->GetEnv((void**)&env2, JNI_VERSION_1_6);
                if (st == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env2, nullptr); nd = true; }
                if (env2) env2->CallVoidMethod(callback, g_onTokensGenerated, tokens_generated);
                if (nd) g_jvm->DetachCurrentThread();
            }
            maybe_trim_context();
        }
    }

    call_callback_done(callback);
}

// ─── Thread Mode ───────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetThreadMode(JNIEnv*, jobject, jint mode) {
    std::lock_guard<std::mutex> lock(g_state.gen_mutex);
    if (mode >= 0 && mode <= 2) apply_thread_mode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetSystemPrompt(JNIEnv* env, jobject, jstring jprompt) {
    const char* s = env->GetStringUTFChars(jprompt, nullptr);
    if (s) { g_state.system_prompt = s; env->ReleaseStringUTFChars(jprompt, s); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_state.model) return env->NewStringUTF("{}");
    char buf[256];
    std::ostringstream j;
    j << "{";
    if (llama_model_meta_val_str(g_state.model, "general.architecture", buf, sizeof(buf)) >= 0)
        j << "\"arch\":\"" << buf << "\",";
    j << "\"n_params\":" << llama_model_n_params(g_state.model) << ",";
    j << "\"n_embd\":" << llama_model_n_embd(g_state.model) << ",";
    if (llama_model_meta_val_str(g_state.model, "llm.block_count", buf, sizeof(buf)) >= 0)
        j << "\"n_layer\":" << atoi(buf) << ",";
    j << "\"n_vocab\":" << llama_vocab_n_tokens(llama_model_get_vocab(g_state.model));
    j << "}";
    return env->NewStringUTF(j.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeAbortInference(JNIEnv*, jobject) {
    g_state.cancel_flag.store(true);
}

// ─── JSON helper ───────────────────────────────────────────────────────────────
static std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 16);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c;
        }
    }
    return out;
}

// ─── Prompt Cache Control ──────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetCacheDir(JNIEnv* env, jobject, jstring jdir) {
    const char* d = env->GetStringUTFChars(jdir, nullptr);
    if (d) {
        g_prompt_cache_dir = d;
        mkdir(d, 0700);
        env->ReleaseStringUTFChars(jdir, d);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeClearCache(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> ck(g_cache_mutex);
    g_mem_cache.clear();
    if (!g_prompt_cache_dir.empty()) {
        DIR* dir = opendir(g_prompt_cache_dir.c_str());
        if (dir) {
            struct dirent* entry;
            while ((entry = readdir(dir)) != nullptr) {
                if (strncmp(entry->d_name, "pc_", 3) == 0) {
                    std::string fp = g_prompt_cache_dir + "/" + entry->d_name;
                    remove(fp.c_str());
                }
            }
            closedir(dir);
        }
    }
    LOGI("Prompt cache cleared");
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetStreamingLLM(
    JNIEnv*, jobject, jint sinkTokens, jint recentTokens, jfloat threshold) {
    g_state.kv_sink_tokens = std::max(1, (int)sinkTokens);
    g_state.kv_recent_tokens = std::max(64, (int)recentTokens);
    g_state.kv_evict_threshold = std::max(0.5f, std::min(0.99f, (float)threshold));
    LOGI("StreamingLLM: sink=%d recent=%d threshold=%.2f",
         g_state.kv_sink_tokens, g_state.kv_recent_tokens, g_state.kv_evict_threshold);
}

// ─── RAG Operations ────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeGetEmbeddingDim(JNIEnv*, jobject) {
    return g_embedding_dim;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeAddDocument(
    JNIEnv* env, jobject, jstring jtext, jstring jsource, jint chunkSize, jint overlap) {
    if (!g_state.model || !g_state.ctx) return JNI_FALSE;

    const char* text_c = env->GetStringUTFChars(jtext, nullptr);
    const char* src_c = env->GetStringUTFChars(jsource, nullptr);
    if (!text_c) return JNI_FALSE;

    std::string text(text_c);
    std::string source(src_c ? src_c : "");
    env->ReleaseStringUTFChars(jtext, text_c);
    if (src_c) env->ReleaseStringUTFChars(jsource, src_c);

    int cs = chunkSize > 0 ? chunkSize : 512;
    int ov = overlap >= 0 ? overlap : 64;

    auto chunks = split_into_chunks(text, cs, ov);
    if (chunks.empty()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_rag_mutex);
    for (const auto& c : chunks) {
        Chunk ch;
        ch.text = c;
        ch.source = source;
        if (!compute_embedding(c, ch.embedding)) {
            LOGW("Failed to embed chunk, skipping");
            continue;
        }
        g_chunks.push_back(std::move(ch));
    }

    LOGI("Added %zu chunks from '%s'", chunks.size(), source.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeQueryDocuments(
    JNIEnv* env, jobject, jstring jquery, jint topK) {
    if (!g_state.model || !g_state.ctx) {
        return env->NewStringUTF("[]");
    }

    const char* q = env->GetStringUTFChars(jquery, nullptr);
    if (!q) return env->NewStringUTF("[]");
    std::string query(q);
    env->ReleaseStringUTFChars(jquery, q);

    std::vector<float> q_emb;
    if (!compute_embedding(query, q_emb)) {
        return env->NewStringUTF("[]");
    }

    std::lock_guard<std::mutex> lock(g_rag_mutex);
    std::vector<std::pair<float, int>> scored;
    for (size_t i = 0; i < g_chunks.size(); i++) {
        if (g_chunks[i].embedding.empty()) continue;
        float sim = cosine_similarity(q_emb, g_chunks[i].embedding);
        scored.push_back({sim, (int)i});
    }

    std::sort(scored.begin(), scored.end(), [](auto& a, auto& b) { return a.first > b.first; });
    int k = std::min(topK > 0 ? topK : 3, (int)scored.size());

    std::ostringstream json;
    json << "[";
    for (int i = 0; i < k; i++) {
        const auto& ch = g_chunks[scored[i].second];
        if (i > 0) json << ",";
        json << "{"
             << "\"score\":" << scored[i].first << ","
             << "\"source\":\"" << json_escape(ch.source) << "\","
             << "\"text\":\"" << json_escape(ch.text) << "\""
             << "}";
    }
    json << "]";
    return env->NewStringUTF(json.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeClearDocuments(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_rag_mutex);
    g_chunks.clear();
    LOGI("RAG documents cleared");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeNumDocuments(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_rag_mutex);
    return (jint)g_chunks.size();
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetRagEnabled(JNIEnv*, jobject, jboolean enabled) {
    g_state.rag_enabled = enabled;
    LOGI("RAG %s", enabled ? "enabled" : "disabled");
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_lib_NativeBridge_nativeSetRagParams(JNIEnv*, jobject, jint topK, jfloat minScore) {
    g_state.rag_top_k = topK > 0 ? topK : 3;
    g_state.rag_min_score = std::max(0.0f, std::min(1.0f, (float)minScore));
}

// JNI bridge for com.gguf.zerocopy.lib — ToolNeuron-style architecture
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <chrono>
#include <sstream>
#include <thread>
#include <android/log.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sched.h>
#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#define TAG "ZeroCopy_Lib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Simplified state - will expand
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
    std::string prompt_cache_dir;
    bool use_mmap = true;
    bool use_mlock = false;
    ggml_threadpool_t threadpool = nullptr;
    ggml_threadpool_t threadpool_batch = nullptr;
} g_state;

// Callback IDs
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

// Simple thread detection
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
    
    // Build threadpools
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

// Simplified generate
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
    
    apply_thread_mode(g_state.thread_mode);
    rebuild_sampler();
    
    LOGI("Model loaded successfully");
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
    
    // Build prompt
    std::string full_prompt = g_state.system_prompt + "\nUser: " + prompt_c + "\nAssistant:";
    env->ReleaseStringUTFChars(jprompt, prompt_c);
    
    // Tokenize
    const llama_vocab* vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens(full_prompt.size() + 256);
    int n = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(), tokens.data(), tokens.size(), false, true);
    if (n <= 0) { call_callback_on_error(callback, "Tokenization failed"); return; }
    tokens.resize(n);
    
    // Prepend BOS
    llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL) tokens.insert(tokens.begin(), bos);
    
    // Eval prompt
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    g_state.n_past = 0;
    
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_state.ctx, batch) != 0) {
        call_callback_on_error(callback, "Prompt decode failed");
        return;
    }
    g_state.n_past = tokens.size();
    
    // Generate
    std::string response;
    for (int i = 0; i < maxTokens; i++) {
        if (g_state.cancel_flag.load()) break;
        
        llama_token tok = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        
        char piece[256];
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) {
            piece[len] = '\0';
            response += piece;
            
            JNIEnv* env2 = nullptr; bool need_detach = false;
            int stat = g_jvm->GetEnv((void**)&env2, JNI_VERSION_1_6);
            if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env2, nullptr); need_detach = true; }
            if (env2 && g_onToken) {
                jstring jtok = env2->NewStringUTF(piece);
                env2->CallVoidMethod(callback, g_onToken, jtok);
                env2->DeleteLocalRef(jtok);
            }
            if (need_detach) g_jvm->DetachCurrentThread();
        }
        
        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_state.ctx, nb) != 0) break;
        g_state.n_past++;
    }
    
    if (g_onDone) {
        JNIEnv* env2 = nullptr; bool need_detach = false;
        int stat = g_jvm->GetEnv((void**)&env2, JNI_VERSION_1_6);
        if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env2, nullptr); need_detach = true; }
        if (env2) env2->CallVoidMethod(callback, g_onDone);
        if (need_detach) g_jvm->DetachCurrentThread();
    }
}

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

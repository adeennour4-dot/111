#include <jni.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <sstream>
#include <thread>
#include <android/log.h>
#include <fstream>

#ifdef __aarch64__
#include <sched.h>
#include <sys/syscall.h>
#endif

#include "llama.h"

// CLIP vision support (requires tools/mtmd to be built)
#if __has_include("clip.h")
  #include "clip.h"
  // Include internal header for clip_image_f32 struct access
  #if __has_include("clip-impl.h")
    #include "clip-impl.h"
  #endif
  // stb_image for loading image files
  #if __has_include("stb_image.h")
    #include "stb_image.h"
  #endif
  #define ZC_HAS_CLIP
#endif

#define LOG_TAG "ZeroCopy_v8"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

struct EngineConfig {
    int      n_ctx          = 4096;
    int      n_batch        = 512;
    int      n_threads      = 0;
    int      n_gpu_layers   = 0;
    int      max_new_tokens = 2048;
    float    temperature    = 0.5f;
    float    top_p          = 0.9f;
    float    min_p          = 0.1f;
    float    repeat_penalty = 1.1f;
    float    freq_penalty   = 0.0f;
    float    pres_penalty   = 0.1f;
    uint32_t seed           = LLAMA_DEFAULT_SEED;
    bool     low_ram_mode   = true;
    bool     flash_attn     = true;
    std::string system_prompt =
        "You are a helpful, concise assistant running on-device. "
        "Respond clearly and directly.";
    std::string mmproj_path = "";
};

static llama_model*   g_model       = nullptr;
static llama_context* g_ctx         = nullptr;
static llama_sampler* g_sampler     = nullptr;
static EngineConfig   g_cfg;
static std::atomic<bool> g_abort    { false };
static bool           g_backend_initialized = false;
static jobject        g_callback    = nullptr;

// Multimodal (vision) support
#ifdef ZC_HAS_CLIP
static struct clip_ctx* g_clip = nullptr;
#else
static void* g_clip = nullptr;
#endif
static std::string g_current_image_path = "";

struct Message { std::string role; std::string content; };
static std::vector<Message> g_history;

static std::vector<int> detect_big_cores() {
    std::vector<int> big_cores;
    std::vector<std::pair<int, int>> core_freqs;
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) { int freq = 0; if (fscanf(f, "%d", &freq) == 1) core_freqs.push_back({cpu, freq}); fclose(f); }
    }
    if (core_freqs.empty()) return big_cores;
    int max_freq = 0;
    for (auto& [id, freq] : core_freqs) if (freq > max_freq) max_freq = freq;
    int threshold = max_freq * 80 / 100;
    for (auto& [id, freq] : core_freqs) if (freq >= threshold) big_cores.push_back(id);
    if (big_cores.empty()) for (auto& [id, freq] : core_freqs) big_cores.push_back(id);
    return big_cores;
}

static void pin_to_big_cores() {
#ifdef __aarch64__
    auto big_cores = detect_big_cores();
    if (big_cores.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : big_cores) CPU_SET(core, &cpuset);
    pid_t tid = syscall(SYS_gettid);
    if (sched_setaffinity(tid, sizeof(cpuset), &cpuset) == 0)
        LOGI("Pinned to %zu big cores", big_cores.size());
#endif
}

static void pin_to_all_cores() {
#ifdef __aarch64__
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) CPU_SET(cpu, &cpuset);
    pid_t tid = syscall(SYS_gettid);
    sched_setaffinity(tid, sizeof(cpuset), &cpuset);
#endif
}

static void boost_priority() {
    if (setpriority(PRIO_PROCESS, 0, -20) == 0)
        LOGI("Priority boosted to -20");
}

static void lock_pages() {
    if (mlockall(MCL_CURRENT | MCL_FUTURE) == 0)
        LOGI("Pages locked in RAM");
}

static void apply_perf_optimizations() {
    boost_priority();
    lock_pages();
    pin_to_big_cores();
}

static inline llama_memory_t get_mem() { return llama_get_memory(g_ctx); }

static void rebuild_sampler() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Correct order: penalties -> temperature -> top-p -> min-p -> distribution
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(64, g_cfg.repeat_penalty, g_cfg.freq_penalty, g_cfg.pres_penalty));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_cfg.temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_cfg.top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_cfg.min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_cfg.seed));
}

static std::string build_chat_prompt() {
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", g_cfg.system_prompt.c_str()});
    for (auto& m : g_history) msgs.push_back({m.role.c_str(), m.content.c_str()});
    // Use the model's actual chat template from metadata, fall back to chatml
    const char * tmpl = g_model ? llama_model_chat_template(g_model, nullptr) : nullptr;
    if (!tmpl) tmpl = "chatml";
    std::vector<char> buf(65536);
    int n = llama_chat_apply_template(tmpl, msgs.data(), (int)msgs.size(), true, buf.data(), (int)buf.size());
    if (n < 0 && strcmp(tmpl, "chatml") != 0) {
        LOGW("Chat template detection failed, falling back to chatml");
        tmpl = "chatml";
        n = llama_chat_apply_template(tmpl, msgs.data(), (int)msgs.size(), true, buf.data(), (int)buf.size());
    }
    if (n < 0) {
        LOGE("Chat template application failed, returning empty prompt");
        return "";
    }
    if (n > (int)buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), (int)msgs.size(), true, buf.data(), (int)buf.size());
        if (n < 0) return "";
    }
    return std::string(buf.data(), n);
}

// Stop sequences — chat template markers that signal the model is starting
// a new turn (should stop generation and truncate at this point).
static const std::vector<std::string> g_stop_sequences = {
    "\n<|im_start|>user",
    "\n<|im_start|>assistant",
    "\n<|im_start|>system",
    "\n<start_of_turn>user",
    "\n<start_of_turn>model",
    "\n<start_of_turn>assistant",
    "\n<|user|>",
    "\n<|assistant|>",
    "<|end|>",
    "<end>",
    "</s>",
    "<im_end>",
};

// Patterns to strip from generated output before saving
static const std::vector<std::string> g_token_patterns = {
    "<|im_start|>", "<|im_end|>", "<|end|>",
    "<|user|>", "<|assistant|>", "<|system|>",
    "<end>", "<im_end>", "</s>",
};

// Strip all token patterns from text
static std::string strip_special_tokens(const std::string& text) {
    std::string result = text;
    for (const auto& pat : g_token_patterns) {
        size_t pos = 0;
        while ((pos = result.find(pat, pos)) != std::string::npos) {
            result.erase(pos, pat.length());
        }
    }
    return result;
}

// Check if any stop sequence appears in the accumulated response
static bool contains_stop_sequence(const std::string& text) {
    for (const auto& seq : g_stop_sequences) {
        if (text.find(seq) != std::string::npos) return true;
    }
    return false;
}

static void call_callback_on_token(const std::string& piece) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return; }
    jmethodID m = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (m) { jstring s = env->NewStringUTF(piece.c_str()); env->CallVoidMethod(g_callback, m, s); env->DeleteLocalRef(s); }
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_kv_cache(int percent) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return; }
    jmethodID m = env->GetMethodID(cls, "onKvCacheUsage", "(I)V");
    if (m) env->CallVoidMethod(g_callback, m, percent);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_tokens_generated(int count) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return; }
    jmethodID m = env->GetMethodID(cls, "onTokensGenerated", "(I)V");
    if (m) env->CallVoidMethod(g_callback, m, count);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_done() {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return; }
    jmethodID m = env->GetMethodID(cls, "onDone", "()V");
    if (m) env->CallVoidMethod(g_callback, m);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_error(const std::string& error) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return; }
    jmethodID m = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    if (m) { jstring s = env->NewStringUTF(error.c_str()); env->CallVoidMethod(g_callback, m, s); env->DeleteLocalRef(s); }
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void release_callback() {
    if (g_callback && g_jvm) {
        JNIEnv* env = nullptr;
        g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setEngineConfigNative(
        JNIEnv*, jobject,
        jint nCtx, jint nBatch, jint maxNewTokens, jfloat temp,
        jfloat topP, jfloat minP, jint nGpuLayers, jint nThreads, jint seed,
        jboolean lowRamMode, jboolean flashAttention) {
    g_cfg.n_ctx          = nCtx;
    g_cfg.n_batch        = (nBatch > 0) ? nBatch : 2048;
    g_cfg.max_new_tokens = maxNewTokens;
    g_cfg.temperature    = temp;
    g_cfg.top_p          = topP;
    g_cfg.min_p          = minP;
    g_cfg.n_gpu_layers   = nGpuLayers;
    g_cfg.n_threads      = nThreads;
    g_cfg.seed           = (seed < 0) ? LLAMA_DEFAULT_SEED : (uint32_t)seed;
    g_cfg.low_ram_mode   = lowRamMode;
    g_cfg.flash_attn     = flashAttention;
    if (g_ctx) rebuild_sampler();
    LOGI("Config: ctx=%d batch=%d gpu=%d threads=%d lowRam=%d flashAttn=%d",
         nCtx, nBatch, nGpuLayers, nThreads, lowRamMode, flashAttention);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setRepeatPenaltyNative(
        JNIEnv*, jobject,
        jfloat repeatPenalty, jfloat freqPenalty, jfloat presPenalty) {
    g_cfg.repeat_penalty = repeatPenalty;
    g_cfg.freq_penalty   = freqPenalty;
    g_cfg.pres_penalty   = presPenalty;
    if (g_ctx) rebuild_sampler();
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setSystemPromptNative(
        JNIEnv* env, jobject, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (s) { g_cfg.system_prompt = s; env->ReleaseStringUTFChars(prompt, s); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_resetContextNative(
        JNIEnv*, jobject) {
    g_history.clear();
    if (g_ctx) llama_memory_clear(get_mem(), true);
    LOGI("Context reset");
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_abortInferenceNative(
        JNIEnv*, jobject) {
    g_abort.store(true);
    LOGI("Inference abort requested");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_loadGgufModelNative(
        JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    if (!g_backend_initialized) { llama_backend_init(); g_backend_initialized = true; }

#ifdef ZC_HAS_CLIP
    if (g_clip)   { clip_free(g_clip);              g_clip   = nullptr; }
#endif
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();
    g_current_image_path = "";

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_cfg.n_gpu_layers;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    int total_cores = (int)std::thread::hardware_concurrency();
    if (total_cores < 1) total_cores = 4;
    int n_threads = (g_cfg.n_threads > 0) ? std::min(g_cfg.n_threads, total_cores) : total_cores;

    // Low RAM mode: reduce n_ctx aggressively, use 4-bit KV cache if available
    int n_ctx = g_cfg.n_ctx;
    if (g_cfg.low_ram_mode) {
        n_ctx = std::min(n_ctx, 2048);
        LOGI("Low RAM mode: n_ctx limited to %d", n_ctx);
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = g_cfg.n_batch;
    cparams.n_ubatch        = std::min(g_cfg.n_batch, 512);
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = g_cfg.flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        g_backend_initialized = false;
        LOGE("Failed to create context");
        return JNI_FALSE;
    }

    rebuild_sampler();
    apply_perf_optimizations();

    LOGI("Model loaded: ctx=%d gpu=%d threads=%d cores=%d lowRam=%d",
         n_ctx, g_cfg.n_gpu_layers, n_threads, total_cores, (int)g_cfg.low_ram_mode);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_loadMmprojNative(
        JNIEnv* env, jobject, jstring path) {
    const char* mmproj_path = env->GetStringUTFChars(path, nullptr);
    if (!mmproj_path) return JNI_FALSE;
    std::string path_copy(mmproj_path);
    env->ReleaseStringUTFChars(path, mmproj_path);

#ifdef ZC_HAS_CLIP
    if (g_clip) { clip_free(g_clip); g_clip = nullptr; }

    // Use clip_init (b9581+ API) instead of deprecated clip_model_load
    struct clip_context_params clip_params;
    clip_params.use_gpu = true;
    clip_params.flash_attn_type = CLIP_FLASH_ATTN_TYPE_AUTO;
    clip_params.image_min_tokens = 0;
    clip_params.image_max_tokens = 0;
    clip_params.warmup = false;
    clip_params.cb_eval = nullptr;
    clip_params.cb_eval_user_data = nullptr;
    clip_params.no_alloc = false;

    struct clip_init_result init_res = clip_init(path_copy.c_str(), clip_params);

    if (!init_res.ctx_v) {
        LOGE("Failed to load mmproj");
        return JNI_FALSE;
    }

    g_clip = init_res.ctx_v;
    g_cfg.mmproj_path = path_copy;
    LOGI("mmproj loaded successfully");
    return JNI_TRUE;
#else
    LOGE("mmproj loading not available - rebuild with CLIP support");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_getModelInfoNative(
        JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");
    char buf[256];
    std::ostringstream j;
    j << "{";
    if (llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf)) >= 0)
        j << "\"arch\":\"" << buf << "\",";
    else j << "\"arch\":\"unknown\",";
    j << "\"n_params\":" << llama_model_n_params(g_model) << ",";
    j << "\"n_embd\":" << llama_model_n_embd(g_model) << ",";
    if (llama_model_meta_val_str(g_model, "llm.block_count", buf, sizeof(buf)) >= 0)
        j << "\"n_layer\":" << atoi(buf) << ",";
    if (llama_model_meta_val_str(g_model, "llm.context_length", buf, sizeof(buf)) >= 0)
        j << "\"ctx_train\":" << atoi(buf) << ",";
    if (llama_model_meta_val_str(g_model, "general.file_type", buf, sizeof(buf)) >= 0) {
        const char* quant = "";
        int ft = atoi(buf);
        switch (ft) {
            case 1: quant = "Q4_0"; break; case 2: quant = "Q4_1"; break;
            case 3: quant = "Q5_0"; break; case 4: quant = "Q5_1"; break;
            case 6: quant = "Q4_K_M"; break; case 7: quant = "Q5_K_M"; break;
            case 8: quant = "Q6_K"; break; case 9: quant = "Q8_0"; break;
            case 10: quant = "F16"; break; case 11: quant = "F32"; break;
            default: quant = buf; break;
        }
        j << "\"quantization\":\"" << quant << "\",";
    }
    j << "\"n_vocab\":" << llama_vocab_n_tokens(llama_model_get_vocab(g_model));
    j << "}";
    return env->NewStringUTF(j.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_benchmarkNative(
        JNIEnv* env, jobject, jint ppTokens, jint tgTokens) {
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"no model\"}");
    const char* test_str = "The quick brown fox jumps over the lazy dog. ";
    std::vector<llama_token> pp_toks(ppTokens);
    int n = llama_tokenize(llama_model_get_vocab(g_model), test_str, strlen(test_str), pp_toks.data(), ppTokens, false, true);
    if (n <= 0) n = std::min(ppTokens, 4);
    pp_toks.resize(n);
    while ((int)pp_toks.size() < ppTokens) {
        auto old = pp_toks;
        for (auto t : old) { pp_toks.push_back(t); if ((int)pp_toks.size() >= ppTokens) break; }
    }
    pp_toks.resize(ppTokens);
    llama_memory_clear(get_mem(), true);
    llama_batch batch = llama_batch_get_one(pp_toks.data(), ppTokens);
    auto pp_start = std::chrono::high_resolution_clock::now();
    llama_decode(g_ctx, batch);
    auto pp_end = std::chrono::high_resolution_clock::now();
    double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    double pp_tps = ppTokens / (pp_ms / 1000.0);

    llama_sampler* bench_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(bench_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    llama_token token = llama_sampler_sample(bench_sampler, g_ctx, -1);
    auto tg_start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < tgTokens; i++) {
        llama_batch tb = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, tb) < 0) break;
        token = llama_sampler_sample(bench_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) break;
    }
    auto tg_end = std::chrono::high_resolution_clock::now();
    double tg_ms  = std::chrono::duration<double, std::milli>(tg_end - tg_start).count();
    double tg_tps = tgTokens / (tg_ms / 1000.0);
    llama_sampler_free(bench_sampler);
    llama_memory_clear(get_mem(), true);

    char result[256];
    snprintf(result, sizeof(result), "{\"pp_tps\":%.1f,\"tg_tps\":%.1f,\"pp_ms\":%.1f,\"tg_ms\":%.1f}", pp_tps, tg_tps, pp_ms, tg_ms);
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_exportChatHistoryNative(
        JNIEnv* env, jobject) {
    std::ostringstream out;
    out << "=== ZeroCopy Chat Export ===\n";
    for (size_t i = 0; i < g_history.size(); i++)
        out << "\n[" << (i + 1) << "] " << g_history[i].role << ":\n" << g_history[i].content << "\n";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_getKvCacheUsageNative(
        JNIEnv*, jobject) {
    if (!g_ctx) return 0;
    // Note: This returns an approximation based on max position.
    // Actual KV cache usage may be lower due to gaps in the sequence.
    llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
    int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
    int total = g_cfg.n_ctx;
    return (total > 0) ? ((used * 100) / total) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_restoreHistoryNative(
        JNIEnv* env, jobject, jstring messagesJson) {
    const char* json = env->GetStringUTFChars(messagesJson, nullptr);
    if (!json) return;

    g_history.clear();
    std::string input(json);
    env->ReleaseStringUTFChars(messagesJson, json);

    size_t pos = 0;
    while ((pos = input.find("{\"role\"", pos)) != std::string::npos) {
        size_t end = input.find("}", pos);
        if (end == std::string::npos) break;
        std::string obj = input.substr(pos, end - pos + 1);
        pos = end + 1;

        size_t role_marker = obj.find("\"role\":\"");
        if (role_marker == std::string::npos) continue;
        auto role_start = role_marker + 8;
        auto role_end = obj.find("\"", role_start);
        if (role_start >= obj.size() || role_end == std::string::npos) continue;
        std::string role = obj.substr(role_start, role_end - role_start);

        size_t content_marker = obj.find("\"content\":\"");
        std::string content;
        if (content_marker != std::string::npos) {
            auto content_start = content_marker + 11;
            if (content_start < obj.size()) {
                bool escaped = false;
                for (size_t i = content_start; i < obj.size(); i++) {
                    char c = obj[i];
                    if (escaped) { content += c; escaped = false; continue; }
                    if (c == '\\') { escaped = true; continue; }
                    if (c == '\"') break;
                    content += c;
                }
            }
        }

        if (!role.empty()) {
            g_history.push_back({role, content});
        }
    }
    LOGI("Restored %zu history messages", g_history.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_executeWithCallbackNative(
        JNIEnv* env, jobject thiz, jstring jprompt, jobject callback) {
    LOGI("executeWithCallbackNative called");
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Engine not ready: model=%p, ctx=%p, sampler=%p", g_model, g_ctx, g_sampler);
        if (callback) {
            jclass cls = env->GetObjectClass(callback);
            jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
            if (onError) { jstring err = env->NewStringUTF("Engine not ready"); env->CallVoidMethod(callback, onError, err); env->DeleteLocalRef(err); }
            env->DeleteLocalRef(cls);
        }
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    if (!user_input) { call_callback_on_error("Failed to read prompt"); return; }

    // Release old callback if exists
    if (g_callback) release_callback();
    // Store global reference immediately to prevent garbage collection
    g_callback = env->NewGlobalRef(callback);
    LOGI("Callback stored as global reference: %p", g_callback);
    g_abort.store(false);

    g_history.push_back({"user", std::string(user_input)});
    env->ReleaseStringUTFChars(jprompt, user_input);

    std::string prompt = build_chat_prompt();
    LOGI("Prompt len=%zu", prompt.size());

    // Log first 200 chars of the rendered prompt for debugging
    LOGI("Prompt (first 200): %s", prompt.substr(0, 200).c_str());

    int n_ctx = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens;
    tokens.resize(n_ctx);
    // add_special=false: let the Jinja template handle BOS (templates like Llama 3
    // already include {{ bos_token }}). parse_special=true: correctly parse special
    // token strings like <|start_header_id|> as single tokens.
    int n_toks = llama_tokenize(llama_model_get_vocab(g_model), prompt.c_str(), prompt.size(), tokens.data(), n_ctx, false, true);
    if (n_toks <= 0) {
        LOGE("Tokenization returned %d tokens, prompt len=%zu", n_toks, prompt.size());
        g_history.pop_back();
        call_callback_on_error("Tokenization failed");
        release_callback();
        return;
    }
    tokens.resize(n_toks);

    // Ensure BOS is present: if the template doesn't include bos_token (e.g., chatml
    // fallback), prepend it manually. This avoids double-BOS when the template
    // already has it, while still adding BOS for templates that don't.
    llama_token bos_token = llama_vocab_bos(llama_model_get_vocab(g_model));
    if (bos_token != LLAMA_TOKEN_NULL && (n_toks == 0 || tokens[0] != bos_token)) {
        tokens.insert(tokens.begin(), bos_token);
        n_toks = (int)tokens.size();
    }

    // Log first 10 tokens for debugging
    std::string tok_debug;
    for (int i = 0; i < std::min(10, n_toks); i++) {
        if (i > 0) tok_debug += " ";
        char piece[32];
        int pn = llama_token_to_piece(llama_model_get_vocab(g_model), tokens[i], piece, sizeof(piece), 0, false);
        tok_debug += std::to_string(tokens[i]) + ":'" + (pn > 0 ? std::string(piece, pn) : "?") + "'";
    }
    LOGI("Tokenized %d tokens, first tokens: %s", n_toks, tok_debug.c_str());

    if ((int)tokens.size() + 64 >= n_ctx) {
        LOGE("Prompt too large: %d tokens >= %d context", (int)tokens.size(), n_ctx);
        g_history.pop_back();
        call_callback_on_error("Prompt exceeds context window");
        release_callback();
        return;
    }

    // Clear the KV cache so the freshly rendered prompt (which already contains
    // the full conversation) is decoded at the correct positions.
    llama_memory_clear(get_mem(), true);

    pin_to_all_cores();
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    int ret = llama_decode(g_ctx, batch);
    if (ret < 0) {
        LOGE("Prompt decode failed with %d for %d tokens", ret, (int)tokens.size());
        call_callback_on_error("Prompt decode failed");
        release_callback();
        return;
    }
    if (ret > 0) {
        LOGW("Prompt decode warning %d for %d tokens", ret, (int)tokens.size());
    }

    // Debug: log first few logit values to diagnose empty output
    {
        float* logits = llama_get_logits_ith(g_ctx, -1);
        int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(g_model));
        if (logits && n_vocab > 0) {
            float max_l = -1e38f;
            llama_token max_id = 0;
            for (int i = 0; i < std::min(n_vocab, 1024); i++) {
                if (logits[i] > max_l) { max_l = logits[i]; max_id = i; }
            }
            // also check EOS token
            llama_token eos = llama_vocab_eos(llama_model_get_vocab(g_model));
            if (eos >= 0 && eos < n_vocab && logits[eos] > max_l) {
                max_l = logits[eos]; max_id = eos;
            }
            LOGI("Logits check: max=%f at token %d (EOS=%d) n_vocab=%d", max_l, max_id, eos, n_vocab);
        }
    }

    {
        llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
        int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
        call_callback_on_kv_cache((g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0);
    }

    pin_to_big_cores();
    std::string response;
    int tokens_generated = 0;
    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_abort.load()) { LOGI("Aborted at token %d", i); break; }

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) {
            if (i == 0) {
                char piece[16];
                int pn = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
                piece[pn > 0 ? pn : 0] = '\0';
                LOGW("First sampled token is EOG (id=%d piece='%s' n_toks=%d prompt_len=%zu)",
                     tok, piece, (int)tokens.size(), prompt.size());
            }
            break;
        }

        char piece[256];
        int n = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            if (n < (int)sizeof(piece)) {
                piece[n] = '\0';
                response += piece;
                call_callback_on_token(std::string(piece, n));
            } else {
                std::vector<char> buf(n + 1);
                llama_token_to_piece(llama_model_get_vocab(g_model), tok, buf.data(), buf.size(), 0, false);
                buf[n] = '\0';
                response += buf.data();
                call_callback_on_token(std::string(buf.data(), n));
            }
            tokens_generated = i + 1;
            call_callback_on_tokens_generated(tokens_generated);
        }

        if (contains_stop_sequence(response)) {
            for (const auto& seq : g_stop_sequences) {
                auto pos = response.find(seq);
                if (pos != std::string::npos) {
                    response = response.substr(0, pos);
                    break;
                }
            }
            break;
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        int r2 = llama_decode(g_ctx, nb);
        if (r2 < 0) { LOGW("Decode failed at token %d with %d", i, r2); break; }

        if ((i & 0xF) == 0) {
            llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
            int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
            call_callback_on_kv_cache((g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0);
        }
    }

    g_history.push_back({"assistant", strip_special_tokens(response)});
    call_callback_on_done();
    LOGI("Inference done: tokens=%d chars=%zu", tokens_generated, response.size());
    release_callback();
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_executeWithImageNative(
        JNIEnv* env, jobject thiz, jstring jprompt, jstring jimagePath, jobject callback) {
    LOGI("executeWithImageNative called");
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Engine not ready for image inference: model=%p, ctx=%p, sampler=%p", g_model, g_ctx, g_sampler);
        if (callback) {
            jclass cls = env->GetObjectClass(callback);
            jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
            if (onError) { jstring err = env->NewStringUTF("Engine not ready"); env->CallVoidMethod(callback, onError, err); env->DeleteLocalRef(err); }
            env->DeleteLocalRef(cls);
        }
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    const char* image_path = env->GetStringUTFChars(jimagePath, nullptr);
    if (!user_input || !image_path) {
        call_callback_on_error("Failed to read prompt/image");
        if (user_input) env->ReleaseStringUTFChars(jprompt, user_input);
        if (image_path) env->ReleaseStringUTFChars(jimagePath, image_path);
        return;
    }

    // Release old callback if exists
    if (g_callback) release_callback();
    // Store global reference immediately to prevent garbage collection
    g_callback = env->NewGlobalRef(callback);
    LOGI("Image callback stored as global reference: %p", g_callback);
    g_abort.store(false);

    g_history.push_back({"user", std::string(user_input)});
    g_current_image_path = std::string(image_path);
    env->ReleaseStringUTFChars(jprompt, user_input);
    env->ReleaseStringUTFChars(jimagePath, image_path);

    std::string prompt = build_chat_prompt();
    LOGI("Image-prompt len=%zu image=%s", prompt.size(), g_current_image_path.c_str());
    LOGI("Image prompt (first 200): %s", prompt.substr(0, 200).c_str());

    int n_ctx = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens;
    tokens.resize(n_ctx);
    int n_toks = llama_tokenize(llama_model_get_vocab(g_model), prompt.c_str(), prompt.size(), tokens.data(), n_ctx, false, true);
    if (n_toks <= 0) {
        LOGE("Image tokenization returned %d tokens", n_toks);
        g_history.pop_back();
        call_callback_on_error("Tokenization failed");
        release_callback();
        return;
    }
    tokens.resize(n_toks);

    llama_token bos_token = llama_vocab_bos(llama_model_get_vocab(g_model));
    if (bos_token != LLAMA_TOKEN_NULL && (n_toks == 0 || tokens[0] != bos_token)) {
        tokens.insert(tokens.begin(), bos_token);
        n_toks = (int)tokens.size();
    }

    LOGI("Image prompt tokenized to %d tokens", n_toks);

    if ((int)tokens.size() + 64 >= n_ctx) {
        LOGE("Image prompt too large: %d tokens >= %d context", (int)tokens.size(), n_ctx);
        g_history.pop_back();
        call_callback_on_error("Prompt exceeds context window");
        release_callback();
        return;
    }

    // Clear the KV cache so the freshly rendered prompt is decoded at correct positions.
    llama_memory_clear(get_mem(), true);

    // Process image through CLIP if available
    std::vector<float> image_embeds;
    int n_image_tokens = 0;
#ifdef ZC_HAS_CLIP
    if (g_clip) {
        int n_threads = g_cfg.n_threads > 0 ? g_cfg.n_threads : 4;

        // Load image from file using stb_image
        int img_width = 0, img_height = 0, img_channels = 0;
        unsigned char * img_data = stbi_load(
            g_current_image_path.c_str(),
            &img_width, &img_height, &img_channels, 3);

        if (img_data && img_width > 0 && img_height > 0) {
            size_t n_pixels = (size_t)img_width * (size_t)img_height;
            std::vector<float> float_pixels(n_pixels * 3);
            for (size_t i = 0; i < n_pixels * 3; i++) {
                float_pixels[i] = img_data[i] / 255.0f;
            }
            stbi_image_free(img_data);

            // Use internal clip_image_f32 API from llama.cpp's clip-impl.h
            struct clip_image_f32 * clip_img = clip_image_f32_init();
            if (clip_img) {
                clip_img->set_size({img_width, img_height}, false, false);
                clip_img->cpy_buf(float_pixels);

                n_image_tokens = clip_n_output_tokens(g_clip, clip_img);
                if (n_image_tokens < 1) n_image_tokens = 256;

                int n_embd = clip_n_mmproj_embd(g_clip);
                if (n_embd < 1) n_embd = llama_model_n_embd(g_model);

                std::vector<float> embeds_vec((size_t)n_image_tokens * (size_t)n_embd, 0.0f);

                bool ok = clip_image_encode(g_clip, n_threads, clip_img, embeds_vec.data());

                clip_image_f32_free(clip_img);

                if (ok) {
                    image_embeds = std::move(embeds_vec);
                    LOGI("Image encoded: %d tokens, %d dims", n_image_tokens, n_embd);
                } else {
                    LOGW("clip_image_encode failed");
                    n_image_tokens = 0;
                }
            } else {
                LOGW("clip_image_f32_init failed");
            }
        } else {
            LOGW("Failed to load image: %s", g_current_image_path.c_str());
        }
    } else {
        LOGW("No mmproj loaded, skipping image processing");
    }
#else
    LOGW("CLIP not available in this build, skipping image processing");
#endif

    // Context shift if needed
    llama_pos cur_max = llama_memory_seq_pos_max(get_mem(), 0);
    int n_ctx_used = (cur_max >= 0) ? (int)(cur_max + 1) : 0;
    int total_needed = n_ctx_used + n_image_tokens + (int)tokens.size() + 128;

    if (total_needed >= g_cfg.n_ctx) {
        int keep = g_cfg.n_ctx / 4;
        int n_discard = (n_ctx_used - keep) / 2;
        if (n_discard > 0) {
            llama_memory_t mem = get_mem();
            llama_memory_seq_rm (mem, 0, keep, keep + n_discard);
            llama_memory_seq_add(mem, 0, keep + n_discard, -1, -n_discard);
            LOGI("Context shift: discarded=%d", n_discard);
        }
    }

    pin_to_all_cores();
    int n_text_toks = (int)tokens.size();

    // Inject image embeddings as a separate decode step before text tokens
    if (!image_embeds.empty() && n_image_tokens > 0) {
        int n_embd = llama_model_n_embd(g_model);
        LOGI("Processing multimodal input: %d image + %d text tokens", n_image_tokens, n_text_toks);

        // Step 1: Decode image embeddings as separate tokens
        std::vector<llama_token> img_tokens(n_image_tokens, llama_vocab_eos(llama_model_get_vocab(g_model)));
        std::vector<int32_t> img_pos(n_image_tokens);
        std::vector<int32_t> img_n_seq_id(n_image_tokens, 1);
        std::vector<llama_seq_id*> img_seq_id(n_image_tokens);
        std::vector<llama_seq_id> img_seq_id_data(n_image_tokens, 0);
        std::vector<int8_t> img_logits(n_image_tokens, 0);
        for (int i = 0; i < n_image_tokens; i++) {
            img_pos[i] = i;
            img_seq_id[i] = &img_seq_id_data[i];
        }

        // embd array: one float per dimension per token
        float* img_embeds_flat = image_embeds.data();

        llama_batch ib;
        ib.n_tokens   = n_image_tokens;
        ib.token      = img_tokens.data();
        ib.embd       = img_embeds_flat;
        ib.pos        = img_pos.data();
        ib.n_seq_id   = img_n_seq_id.data();
        ib.seq_id     = img_seq_id.data();
        ib.logits     = img_logits.data();

        if (llama_decode(g_ctx, ib) < 0) {
            call_callback_on_error("Image embedding decode failed");
            release_callback();
            return;
        }

        // Step 2: Decode text tokens after image embeddings
        std::vector<int32_t> text_pos(n_text_toks);
        std::vector<int32_t> text_n_seq_id(n_text_toks, 1);
        std::vector<llama_seq_id*> text_seq_id(n_text_toks);
        std::vector<llama_seq_id> text_seq_id_data(n_text_toks, 0);
        std::vector<int8_t> text_logits(n_text_toks, 0);
        for (int i = 0; i < n_text_toks; i++) {
            text_pos[i] = n_image_tokens + i;
            text_seq_id[i] = &text_seq_id_data[i];
        }
        // We only need logits for the LAST token of the prompt (to sample the
        // first generated token from). Without this, llama_decode computes no
        // logits at all and llama_sampler_sample() reads stale/garbage state,
        // producing empty or nonsensical output for every vision request.
        if (n_text_toks > 0) text_logits[n_text_toks - 1] = 1;

        llama_batch tb;
        tb.n_tokens   = n_text_toks;
        tb.token      = tokens.data();
        tb.embd       = nullptr;
        tb.pos        = text_pos.data();
        tb.n_seq_id   = text_n_seq_id.data();
        tb.seq_id     = text_seq_id.data();
        tb.logits     = text_logits.data();

        if (llama_decode(g_ctx, tb) < 0) {
            call_callback_on_error("Text prompt decode after image failed");
            release_callback();
            return;
        }

        // Debug logits
        {
            float* logits = llama_get_logits_ith(g_ctx, -1);
            int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(g_model));
            if (logits && n_vocab > 0) {
                float max_l = -1e38f;
                for (int i = 0; i < std::min(n_vocab, 1024); i++) {
                    if (logits[i] > max_l) max_l = logits[i];
                }
                llama_token eos = llama_vocab_eos(llama_model_get_vocab(g_model));
                if (eos >= 0 && eos < n_vocab && logits[eos] > max_l) max_l = logits[eos];
                LOGI("Image logits max=%f at n_vocab=%d", max_l, n_vocab);
            }
        }
    } else {
        llama_batch batch = llama_batch_get_one(tokens.data(), n_text_toks);
        if (llama_decode(g_ctx, batch) < 0) {
            call_callback_on_error("Prompt decode failed");
            release_callback();
            return;
        }

        // Debug logits
        {
            float* logits = llama_get_logits_ith(g_ctx, -1);
            int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(g_model));
            if (logits && n_vocab > 0) {
                float max_l = -1e38f;
                for (int i = 0; i < std::min(n_vocab, 1024); i++) {
                    if (logits[i] > max_l) max_l = logits[i];
                }
                llama_token eos = llama_vocab_eos(llama_model_get_vocab(g_model));
                if (eos >= 0 && eos < n_vocab && logits[eos] > max_l) max_l = logits[eos];
                LOGI("Image text-only logits max=%f at n_vocab=%d", max_l, n_vocab);
            }
        }
    }

    {
        llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
        int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
        call_callback_on_kv_cache((g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0);
    }

    g_current_image_path = "";
    pin_to_big_cores();
    std::string response;
    int tokens_generated = 0;
    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_abort.load()) { LOGI("Aborted at token %d", i); break; }

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) {
            if (i == 0) {
                char piece[16];
                int pn = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
                piece[pn > 0 ? pn : 0] = '\0';
                LOGW("Image: First sampled token is EOG (id=%d piece='%s' n_toks=%d prompt_len=%zu)",
                     tok, piece, (int)tokens.size(), prompt.size());
            }
            break;
        }

        char piece[256];
        int n = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            if (n < (int)sizeof(piece)) {
                piece[n] = '\0';
                response += piece;
                call_callback_on_token(std::string(piece, n));
            } else {
                std::vector<char> buf(n + 1);
                llama_token_to_piece(llama_model_get_vocab(g_model), tok, buf.data(), buf.size(), 0, false);
                buf[n] = '\0';
                response += buf.data();
                call_callback_on_token(std::string(buf.data(), n));
            }
            tokens_generated = i + 1;
            call_callback_on_tokens_generated(tokens_generated);
        }

        if (contains_stop_sequence(response)) {
            for (const auto& seq : g_stop_sequences) {
                auto pos = response.find(seq);
                if (pos != std::string::npos) {
                    response = response.substr(0, pos);
                    break;
                }
            }
            break;
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        int r2 = llama_decode(g_ctx, nb);
        if (r2 < 0) { LOGW("Image: Decode failed at token %d with %d", i, r2); break; }

        if ((i & 0xF) == 0) {
            llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
            int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
            call_callback_on_kv_cache((g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0);
        }
    }

    g_history.push_back({"assistant", strip_special_tokens(response)});
    call_callback_on_done();
    LOGI("Image inference done: tokens=%d chars=%zu", tokens_generated, response.size());
    release_callback();
}

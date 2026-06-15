#include <jni.h>
#include <string>
#include <sstream>
#include <atomic>
#include <mutex>
#include <chrono>
#include <thread>
#include <android/log.h>

#define LOG_TAG "ZeroCopy-MNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "llm/llm.hpp"
using namespace MNN::Transformer;

static JavaVM* g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static Llm* g_llm = nullptr;
static std::atomic<bool> g_model_loaded{false};
static std::atomic<bool> g_stop_requested{false};
static std::atomic<bool> g_inference_done{true};
static std::atomic<int> g_tokens_generated{0};
static std::mutex g_mutex;
static std::string g_partial_buffer;
static std::string g_full_response;
static std::vector<std::pair<std::string, std::string>> g_history;
static std::string g_system_prompt = "You are a helpful assistant.";
static jobject g_callback = nullptr;

struct MnnConfig {
    int n_ctx = 8192;
    int max_new_tokens = 4096;
    float temperature = 0.7f;
    float repeat_penalty = 1.1f;
    std::string backend = "cpu";
};
static MnnConfig g_cfg;

static std::string quote(const std::string& s) { return "\"" + s + "\""; }
static std::string num(float f) { char buf[32]; snprintf(buf, sizeof(buf), "%g", (double)f); return buf; }
static std::string num(int i) { return std::to_string(i); }
static std::string bol(bool b) { return b ? "true" : "false"; }

static std::string buildJson(std::initializer_list<std::pair<const char*, std::string>> items) {
    std::string json = "{";
    bool first = true;
    for (auto& [k, v] : items) {
        if (!first) json += ",";
        first = false;
        json += "\"" + std::string(k) + "\":" + v;
    }
    json += "}";
    return json;
}

static void release_callback() {
    if (g_callback && g_jvm) {
        JNIEnv* env = nullptr;
        g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
}

static void call_callback_on_token(const std::string& text) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr; bool need_detach = false;
    int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) { g_jvm->AttachCurrentThread(&env, nullptr); need_detach = true; }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID m = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (m) { jstring s = env->NewStringUTF(text.c_str()); env->CallVoidMethod(g_callback, m, s); env->DeleteLocalRef(s); }
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
    jmethodID m = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    if (m) { jstring s = env->NewStringUTF(error.c_str()); env->CallVoidMethod(g_callback, m, s); env->DeleteLocalRef(s); }
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
    jmethodID m = env->GetMethodID(cls, "onTokensGenerated", "(I)V");
    if (m) env->CallVoidMethod(g_callback, m, count);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnSetConfigNative(
    JNIEnv*, jobject, jint nCtx, jint maxNewTokens, jfloat temperature, jfloat repeatPenalty) {
    g_cfg.n_ctx = nCtx;
    g_cfg.max_new_tokens = maxNewTokens;
    g_cfg.temperature = temperature;
    g_cfg.repeat_penalty = repeatPenalty;
    LOGI("Config: ctx=%d max=%d temp=%.2f rep=%.2f", nCtx, maxNewTokens, temperature, repeatPenalty);
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnSetSystemPromptNative(
    JNIEnv* env, jobject, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (s) { g_system_prompt = s; env->ReleaseStringUTFChars(prompt, s); }
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnLoadModel(
    JNIEnv* env, jobject, jstring path) {
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading MNN model: %s", model_path);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_llm) { delete g_llm; g_llm = nullptr; }

    g_llm = Llm::createLLM(model_path);
    env->ReleaseStringUTFChars(path, model_path);

    if (!g_llm) { LOGE("createLLM failed"); return JNI_FALSE; }

    std::string config = "{\"use_mmap\":true,\"precision\":\"low\",\"backend_type\":\"";
    config += g_cfg.backend;
    config += "\"}";
    g_llm->set_config(config);

    if (!g_llm->load()) {
        LOGE("Model load() failed");
        delete g_llm; g_llm = nullptr;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    g_history.clear();
    g_history.emplace_back("system", g_system_prompt);
    LOGI("MNN model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnExecuteInference(
    JNIEnv* env, jobject, jstring jprompt, jobject callback) {
    const char* prompt_str = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_str) return;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested = false;
        g_inference_done = false;
        g_tokens_generated = 0;
        g_partial_buffer.clear();
        g_full_response.clear();
    }

    if (g_callback) release_callback();
    g_callback = env->NewGlobalRef(callback);

    g_history.emplace_back("user", std::string(prompt_str));
    env->ReleaseStringUTFChars(jprompt, prompt_str);

    int tokens_generated = 0;
    std::string response;

    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_stop_requested) { LOGI("Aborted at token %d", i); break; }

        g_llm->generate(1);
        tokens_generated = i + 1;
        g_tokens_generated = tokens_generated;

        auto* ctx = g_llm->getContext();
        if (ctx != nullptr && ctx->current_token > 0) {
            std::string piece = g_llm->tokenizer_decode(ctx->current_token);
            response += piece;
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                g_partial_buffer += piece;
                g_full_response = response;
            }
            call_callback_on_token(piece);
            call_callback_on_tokens_generated(tokens_generated);
        }

        if (ctx != nullptr && (ctx->status == LlmStatus::NORMAL_FINISHED || ctx->status == LlmStatus::MAX_TOKENS_FINISHED))
            break;
    }

    g_history.emplace_back("assistant", response);

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_inference_done = true;
    }

    call_callback_on_done();
    LOGI("Inference done: tokens=%d", tokens_generated);
    release_callback();
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnAbortInference(JNIEnv*, jobject) {
    LOGI("Abort requested");
    g_stop_requested = true;
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnResetContext(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_history.clear();
    g_history.emplace_back("system", g_system_prompt);
    g_partial_buffer.clear();
    g_full_response.clear();
    g_tokens_generated = 0;
    g_inference_done = true;
    if (g_llm) g_llm->reset();
    LOGI("Context reset");
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetModelInfo(JNIEnv* env, jobject) {
    if (!g_llm || !g_model_loaded) return env->NewStringUTF("{}");
    auto* ctx = g_llm->getContext();
    std::string info = buildJson({
        {"engine", quote("MNN")},
        {"model_loaded", bol(true)},
        {"prompt_len", ctx ? num(ctx->prompt_len) : num(0)},
        {"gen_seq_len", ctx ? num(ctx->gen_seq_len) : num(0)},
        {"max_tokens", num(g_cfg.max_new_tokens)},
        {"n_ctx", num(g_cfg.n_ctx)}
    });
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnBenchmark(
    JNIEnv* env, jobject, jint ppTokens, jint tgTokens) {
    if (!g_llm || !g_model_loaded)
        return env->NewStringUTF("{\"error\":\"Model not loaded\"}");

    std::vector<int> tokens(ppTokens, 16);
    auto start = std::chrono::high_resolution_clock::now();
    g_llm->response(tokens, nullptr, nullptr, 1);
    auto end = std::chrono::high_resolution_clock::now();
    auto* ctx = g_llm->getContext();
    float prefill_ms = ctx ? (float)ctx->prefill_us / 1000.0f : 0;
    float prefill_tps = (prefill_ms > 0) ? (float)ppTokens / (prefill_ms / 1000.0f) : 0;

    std::vector<int> gen_tokens(1, 16);
    start = std::chrono::high_resolution_clock::now();
    g_llm->response(gen_tokens, nullptr, nullptr, tgTokens);
    end = std::chrono::high_resolution_clock::now();
    ctx = g_llm->getContext();
    float decode_ms = ctx ? (float)ctx->decode_us / 1000.0f : 0;
    float decode_tps = (decode_ms > 0) ? (float)tgTokens / (decode_ms / 1000.0f) : 0;

    std::string result = buildJson({
        {"prefill_tokens", num((int)ppTokens)},
        {"prefill_ms", num(prefill_ms)},
        {"prefill_tps", num(prefill_tps)},
        {"decode_tokens", num((int)tgTokens)},
        {"decode_ms", num(decode_ms)},
        {"decode_tps", num(decode_tps)}
    });
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnExportChatHistory(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string export_str;
    for (auto& item : g_history) export_str += "[" + item.first + "]: " + item.second + "\n";
    return env->NewStringUTF(export_str.c_str());
}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetTokensGenerated(JNIEnv*, jobject) {
    return g_tokens_generated.load();
}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetKvCacheUsage(JNIEnv*, jobject) {
    if (!g_llm || !g_model_loaded) return 0;
    auto* ctx = g_llm->getContext();
    if (!ctx) return 0;
    int used = ctx->prompt_len + ctx->gen_seq_len;
    return (used > 0 && g_cfg.n_ctx > 0) ? (int)((float)used / g_cfg.n_ctx * 100.0f) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnIsInferenceDone(JNIEnv*, jobject) {
    return g_inference_done ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

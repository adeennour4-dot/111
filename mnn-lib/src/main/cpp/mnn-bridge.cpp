#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <chrono>
#include <vector>

#include "llm/llm.hpp"

#define TAG "MnnBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static MNN::Transformer::Llm *s_llm = nullptr;
static std::mutex s_mutex;
static std::atomic<bool> s_inference_running{false};
static std::atomic<int> s_tokens_generated{0};

static JavaVM *s_jvm = nullptr;
static jobject s_callback_obj = nullptr;
static jclass s_callback_cls = nullptr;
static jmethodID s_on_token = nullptr;
static jmethodID s_on_done = nullptr;
static jmethodID s_on_error = nullptr;
static jmethodID s_on_kv_usage = nullptr;
static jmethodID s_on_tokens_gen = nullptr;

static JNIEnv *getEnv() {
    if (!s_jvm) return nullptr;
    JNIEnv *env;
    int stat = s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) {
        if (s_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}

static void notifyToken(const std::string &token) {
    JNIEnv *env = getEnv();
    if (env && s_callback_obj && s_on_token) {
        jstring jt = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(s_callback_obj, s_on_token, jt);
        env->DeleteLocalRef(jt);
    }
}

static void notifyDone() {
    JNIEnv *env = getEnv();
    if (env && s_callback_obj && s_on_done) {
        env->CallVoidMethod(s_callback_obj, s_on_done);
    }
    s_inference_running = false;
}

static void notifyError(const std::string &error) {
    JNIEnv *env = getEnv();
    if (env && s_callback_obj && s_on_error) {
        jstring je = env->NewStringUTF(error.c_str());
        env->CallVoidMethod(s_callback_obj, s_on_error, je);
        env->DeleteLocalRef(je);
    }
    s_inference_running = false;
}

static void notifyKvUsage(int percent) {
    JNIEnv *env = getEnv();
    if (env && s_callback_obj && s_on_kv_usage) {
        env->CallVoidMethod(s_callback_obj, s_on_kv_usage, percent);
    }
}

static void notifyTokensGenerated(int count) {
    JNIEnv *env = getEnv();
    if (env && s_callback_obj && s_on_tokens_gen) {
        env->CallVoidMethod(s_callback_obj, s_on_tokens_gen, count);
    }
    s_tokens_generated = count;
}

static std::string readFile(const std::string &path) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return "";
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::string result(len, '\0');
    fread(result.data(), 1, len, f);
    fclose(f);
    return result;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    if (s_llm) {
        delete s_llm;
        s_llm = nullptr;
    }
    if (s_callback_obj) {
        JNIEnv *env = getEnv();
        if (env) env->DeleteGlobalRef(s_callback_obj);
        s_callback_obj = nullptr;
    }
    if (s_callback_cls) {
        JNIEnv *env = getEnv();
        if (env) env->DeleteGlobalRef(s_callback_cls);
        s_callback_cls = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnLoadModel(JNIEnv *env, jobject,
                                                                jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    std::string modelPath(path);
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("Loading MNN model from: %s", modelPath.c_str());

    std::lock_guard<std::mutex> lock(s_mutex);

    if (s_llm) {
        delete s_llm;
        s_llm = nullptr;
    }

    std::string configPath = modelPath + "/config.json";
    std::string configJson = readFile(configPath);
    if (configJson.empty()) {
        auto pos = modelPath.rfind('/');
        if (pos != std::string::npos) {
            configPath = modelPath.substr(0, pos + 1) + "config.json";
            configJson = readFile(configPath);
        }
    }

    try {
        s_llm = MNN::Transformer::Llm::createLLM(modelPath);
        if (!s_llm) {
            LOGE("Failed to create LLM instance");
            return JNI_FALSE;
        }

        if (!configJson.empty()) {
            s_llm->set_config(configJson);
        }

        s_llm->load();
        LOGI("MNN model loaded successfully");
        return JNI_TRUE;
    } catch (const std::exception &e) {
        LOGE("Failed to load MNN model: %s", e.what());
        if (s_llm) {
            delete s_llm;
            s_llm = nullptr;
        }
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnExecuteInference(
    JNIEnv *env, jobject, jstring jprompt, jobject callback) {
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string fullPrompt(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    if (!s_llm) {
        LOGE("Model not loaded");
        jclass cbCls = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbCls, "onError", "(Ljava/lang/String;)V");
        jstring err = env->NewStringUTF("Model not loaded");
        env->CallVoidMethod(callback, onError, err);
        env->DeleteLocalRef(err);
        env->DeleteLocalRef(cbCls);
        return;
    }

    if (s_callback_obj) env->DeleteGlobalRef(s_callback_obj);
    if (s_callback_cls) env->DeleteGlobalRef(s_callback_cls);
    s_callback_obj = env->NewGlobalRef(callback);
    s_callback_cls = (jclass)env->NewGlobalRef(env->GetObjectClass(callback));
    s_on_token = env->GetMethodID(s_callback_cls, "onToken", "(Ljava/lang/String;)V");
    s_on_done = env->GetMethodID(s_callback_cls, "onDone", "()V");
    s_on_error = env->GetMethodID(s_callback_cls, "onError", "(Ljava/lang/String;)V");
    s_on_kv_usage = env->GetMethodID(s_callback_cls, "onKvCacheUsage", "(I)V");
    s_on_tokens_gen = env->GetMethodID(s_callback_cls, "onTokensGenerated", "(I)V");

    s_inference_running = true;
    s_tokens_generated = 0;

    std::thread([fullPrompt]() {
        try {
            auto inputIds = s_llm->tokenizer_encode(fullPrompt);
            s_llm->generate(inputIds, -1);

            const auto *ctx = s_llm->getContext();
            if (ctx) {
                int count = 0;
                for (int tokenId : ctx->output_tokens) {
                    if (!s_inference_running) {
                        s_llm->reset();
                        break;
                    }
                    count++;
                    std::string tokenStr = s_llm->tokenizer_decode(tokenId);
                    if (!tokenStr.empty()) {
                        notifyToken(tokenStr);
                    }
                    if (count % 10 == 0) {
                        int maxSeq = ctx->all_seq_len > 0 ? ctx->all_seq_len : 1;
                        notifyKvUsage(count * 100 / maxSeq);
                    }
                    notifyTokensGenerated(count);
                }
            }
            notifyDone();
        } catch (const std::exception &e) {
            notifyError(e.what());
        }
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnAbortInference(JNIEnv *, jobject) {
    s_inference_running = false;
    if (s_llm) {
        try { s_llm->reset(); } catch (...) {}
    }
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnResetContext(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_llm) {
        try { s_llm->reset(); } catch (...) {}
    }
    s_inference_running = false;
    s_tokens_generated = 0;
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetModelInfo(JNIEnv *env, jobject) {
    if (!s_llm) {
        return env->NewStringUTF("{}");
    }
    try {
        const auto *ctx = s_llm->getContext();
        std::string info = "{";
        info += "\"arch\":\"mnn\"";
        if (ctx) {
            info += ",\"ctx_train\":" + std::to_string(ctx->all_seq_len);
            info += ",\"gen_seq_len\":" + std::to_string(ctx->gen_seq_len);
            info += ",\"prompt_len\":" + std::to_string(ctx->prompt_len);
        }
        info += ",\"n_params\":0";
        info += ",\"n_layer\":0";
        info += ",\"n_embd\":0";
        info += ",\"n_vocab\":0";
        info += ",\"quantization\":\"unknown\"";
        info += "}";
        return env->NewStringUTF(info.c_str());
    } catch (...) {
        return env->NewStringUTF("{}");
    }
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnBenchmark(JNIEnv *env, jobject,
                                                                jint ppTokens, jint tgTokens) {
    if (!s_llm) {
        return env->NewStringUTF("{\"error\":\"model_not_loaded\"}");
    }

    std::string result = "{";
    try {
        std::string dummyPrompt(ppTokens, 'x');
        auto inputIds = s_llm->tokenizer_encode(dummyPrompt);

        auto prefillStart = std::chrono::high_resolution_clock::now();
        s_llm->generate(inputIds, 0);
        auto prefillEnd = std::chrono::high_resolution_clock::now();

        auto decodeStart = std::chrono::high_resolution_clock::now();
        s_llm->generate({}, tgTokens);
        auto decodeEnd = std::chrono::high_resolution_clock::now();

        auto prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(prefillEnd - prefillStart).count();
        auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(decodeEnd - decodeStart).count();

        float prefillTps = prefillMs > 0 ? (ppTokens * 1000.0f / prefillMs) : 0.0f;
        float decodeTps = decodeMs > 0 ? (tgTokens * 1000.0f / decodeMs) : 0.0f;

        result += "\"prefill_tps\":" + std::to_string(prefillTps) + ",";
        result += "\"decode_tps\":" + std::to_string(decodeTps) + ",";
        result += "\"prefill_ms\":" + std::to_string(prefillMs) + ",";
        result += "\"decode_ms\":" + std::to_string(decodeMs);
    } catch (const std::exception &e) {
        result += "\"error\":\"" + std::string(e.what()) + "\"";
    }
    result += "}";
    s_llm->reset();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnSetConfigNative(
    JNIEnv *, jobject, jint, jint maxNewTokens, jfloat temperature, jfloat repeatPenalty) {
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_llm) {
        try {
            std::string config = "{";
            config += "\"max_new_tokens\":" + std::to_string(maxNewTokens) + ",";
            config += "\"temperature\":" + std::to_string(temperature) + ",";
            config += "\"repetition_penalty\":" + std::to_string(repeatPenalty);
            config += "}";
            s_llm->set_config(config);
        } catch (...) {}
    }
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnSetSystemPromptNative(JNIEnv *, jobject, jstring) {}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetKvCacheUsage(JNIEnv *, jobject) {
    if (!s_llm) return 0;
    try {
        const auto *ctx = s_llm->getContext();
        if (ctx && ctx->all_seq_len > 0) {
            return s_tokens_generated.load() * 100 / ctx->all_seq_len;
        }
        return 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetTokensGenerated(JNIEnv *, jobject) {
    return s_tokens_generated.load();
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnIsInferenceDone(JNIEnv *, jobject) {
    return s_inference_running.load() ? JNI_FALSE : JNI_TRUE;
}

}

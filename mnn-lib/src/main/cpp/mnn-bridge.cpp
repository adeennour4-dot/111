#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <chrono>

#define TAG "MnnBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "llm.hpp"

static MNN::Transformer::Llm *s_llm = nullptr;
static std::mutex s_mutex;
static std::atomic<bool> s_inference_running{false};
static std::atomic<int> s_tokens_generated{0};

static JavaVM *s_jvm = nullptr;
static JNIEnv *s_callback_env = nullptr;
static jobject s_callback_obj = nullptr;
static jmethodID s_on_token = nullptr;
static jmethodID s_on_done = nullptr;
static jmethodID s_on_error = nullptr;
static jmethodID s_on_kv_usage = nullptr;
static jmethodID s_on_tokens_gen = nullptr;
static jclass s_callback_cls = nullptr;

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

static void notifyToken(const std::string &token) {
    if (s_callback_env && s_callback_obj && s_on_token) {
        jstring jtoken = s_callback_env->NewStringUTF(token.c_str());
        s_callback_env->CallVoidMethod(s_callback_obj, s_on_token, jtoken);
        s_callback_env->DeleteLocalRef(jtoken);
    }
}

static void notifyDone() {
    if (s_callback_env && s_callback_obj && s_on_done) {
        s_callback_env->CallVoidMethod(s_callback_obj, s_on_done);
    }
    s_inference_running = false;
}

static void notifyError(const std::string &error) {
    if (s_callback_env && s_callback_obj && s_on_error) {
        jstring jerror = s_callback_env->NewStringUTF(error.c_str());
        s_callback_env->CallVoidMethod(s_callback_obj, s_on_error, jerror);
        s_callback_env->DeleteLocalRef(jerror);
    }
    s_inference_running = false;
}

static void notifyKvUsage(int percent) {
    if (s_callback_env && s_callback_obj && s_on_kv_usage) {
        s_callback_env->CallVoidMethod(s_callback_obj, s_on_kv_usage, percent);
    }
}

static void notifyTokensGenerated(int count) {
    if (s_callback_env && s_callback_obj && s_on_tokens_gen) {
        s_callback_env->CallVoidMethod(s_callback_obj, s_on_tokens_gen, count);
    }
    s_tokens_generated = count;
}

// Attach current thread to JVM and get callback env
static JNIEnv *getCallbackEnv() {
    if (!s_jvm) return nullptr;
    JNIEnv *env;
    int getEnvStat = s_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (s_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
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
        JNIEnv *env = getCallbackEnv();
        if (env) env->DeleteGlobalRef(s_callback_obj);
        s_callback_obj = nullptr;
    }
    if (s_callback_cls) {
        JNIEnv *env = getCallbackEnv();
        if (env) env->DeleteGlobalRef(s_callback_cls);
        s_callback_cls = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnLoadModel(JNIEnv *env, jobject /*thiz*/,
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

    // Read config.json from model directory
    std::string configPath = modelPath + "/config.json";
    std::string configJson = readFile(configPath);
    if (configJson.empty()) {
        // Try model directory parent
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

        // Apply config
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
    JNIEnv *env, jobject /*thiz*/, jstring jprompt, jobject callback) {
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string fullPrompt(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    if (!s_llm) {
        LOGE("Model not loaded");
        // Call onError on callback
        jclass cbCls = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbCls, "onError", "(Ljava/lang/String;)V");
        jstring err = env->NewStringUTF("Model not loaded");
        env->CallVoidMethod(callback, onError, err);
        env->DeleteLocalRef(err);
        env->DeleteLocalRef(cbCls);
        return;
    }

    // Store callback reference
    if (s_callback_obj) {
        env->DeleteGlobalRef(s_callback_obj);
    }
    if (s_callback_cls) {
        env->DeleteGlobalRef(s_callback_cls);
    }
    s_callback_obj = env->NewGlobalRef(callback);
    s_callback_cls = (jclass)env->NewGlobalRef(env->GetObjectClass(callback));
    s_on_token = env->GetMethodID(s_callback_cls, "onToken", "(Ljava/lang/String;)V");
    s_on_done = env->GetMethodID(s_callback_cls, "onDone", "()V");
    s_on_error = env->GetMethodID(s_callback_cls, "onError", "(Ljava/lang/String;)V");
    s_on_kv_usage = env->GetMethodID(s_callback_cls, "onKvCacheUsage", "(I)V");
    s_on_tokens_gen = env->GetMethodID(s_callback_cls, "onTokensGenerated", "(I)V");

    s_callback_env = getCallbackEnv();
    s_inference_running = true;
    s_tokens_generated = 0;

    // Run inference on a background thread so JNI call returns immediately
    std::thread([fullPrompt]() {
        try {
            // Start generation
            std::string prompt = fullPrompt + "\n";
            s_llm->response(prompt.c_str(), nullptr, nullptr, 1);

            const int kvCheckInterval = 10;
            int tokenCount = 0;

            while (true) {
                if (!s_inference_running) {
                    s_llm->reset();
                    break;
                }

                int tokenId = s_llm->generate(1);
                if (tokenId == 0) {
                    break;
                }

                tokenCount++;

                // Decode token
                std::string tokenStr;
                try {
                    tokenStr = s_llm->tokenizer()->decode(tokenId);
                } catch (...) {
                    tokenStr = "";
                }

                if (!tokenStr.empty()) {
                    notifyToken(tokenStr);
                }

                if (tokenCount % kvCheckInterval == 0) {
                    int kvPct = (tokenCount * 100) / s_llm->getMaxStep();
                    notifyKvUsage(kvPct);
                }

                notifyTokensGenerated(tokenCount);
            }

            notifyDone();
        } catch (const std::exception &e) {
            notifyError(e.what());
        }
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnAbortInference(JNIEnv *env, jobject /*thiz*/) {
    s_inference_running = false;
    if (s_llm) {
        try {
            s_llm->reset();
        } catch (...) {
        }
    }
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnResetContext(JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_llm) {
        try {
            s_llm->reset();
        } catch (...) {
        }
    }
    s_inference_running = false;
    s_tokens_generated = 0;
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetModelInfo(JNIEnv *env,
                                                                   jobject /*thiz*/) {
    if (!s_llm) {
        return env->NewStringUTF("{}");
    }
    try {
        std::string info = "{";
        auto *context = s_llm->getContext();
        if (context) {
            try {
                info += "\"arch\":\"mnn\",";

                int nParams = 0;
                info += "\"n_params\":" + std::to_string(nParams) + ",";

                int nLayers = 0;
                info += "\"n_layer\":" + std::to_string(nLayers) + ",";

                int nEmbeds = 0;
                info += "\"n_embd\":" + std::to_string(nEmbeds) + ",";

                int ctxTrain = s_llm->getMaxStep();
                info += "\"ctx_train\":" + std::to_string(ctxTrain) + ",";

                int nVocab = 0;
                info += "\"n_vocab\":" + std::to_string(nVocab) + ",";

                info += "\"quantization\":\"unknown\"";

            } catch (...) {
            }
        }
        info += "}";
        return env->NewStringUTF(info.c_str());
    } catch (...) {
        return env->NewStringUTF("{}");
    }
}

JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnBenchmark(JNIEnv *env, jobject /*thiz*/,
                                                                jint ppTokens, jint tgTokens) {
    if (!s_llm) {
        return env->NewStringUTF("{\"error\":\"model_not_loaded\"}");
    }

    std::string result = "{";
    try {
        // Prefill benchmark
        auto startPrefill = std::chrono::high_resolution_clock::now();
        std::string dummyPrompt(ppTokens, 'x');
        s_llm->response(dummyPrompt.c_str(), nullptr, nullptr, 0);
        auto endPrefill = std::chrono::high_resolution_clock::now();
        auto prefillMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(endPrefill - startPrefill)
                .count();

        // Decode benchmark
        auto startDecode = std::chrono::high_resolution_clock::now();
        for (int i = 0; i < tgTokens; i++) {
            if (s_llm->generate(1) == 0) break;
        }
        auto endDecode = std::chrono::high_resolution_clock::now();
        auto decodeMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(endDecode - startDecode).count();

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
    JNIEnv *env, jobject /*thiz*/, jint nCtx, jint maxNewTokens, jfloat temperature,
    jfloat repeatPenalty) {
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_llm) {
        try {
            std::string config = "{";
            config += "\"max_new_tokens\":" + std::to_string(maxNewTokens) + ",";
            config += "\"temperature\":" + std::to_string(temperature) + ",";
            config += "\"repetition_penalty\":" + std::to_string(repeatPenalty);
            config += "}";
            s_llm->set_config(config);
        } catch (...) {
        }
    }
}

JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnSetSystemPromptNative(
    JNIEnv *env, jobject /*thiz*/, jstring jprompt) {
    // MNN LLM API does not have a direct system prompt method
    // System prompt is typically prepended to input
}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetKvCacheUsage(JNIEnv *env,
                                                                      jobject /*thiz*/) {
    if (!s_llm) return 0;
    try {
        return s_tokens_generated.load() * 100 / s_llm->getMaxStep();
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnGetTokensGenerated(JNIEnv *env,
                                                                         jobject /*thiz*/) {
    return s_tokens_generated.load();
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_MnnEngine_mnnIsInferenceDone(JNIEnv *env,
                                                                      jobject /*thiz*/) {
    return s_inference_running.load() ? JNI_FALSE : JNI_TRUE;
}

} // extern "C"

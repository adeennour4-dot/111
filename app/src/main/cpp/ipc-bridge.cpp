// ─────────────────────────────────────────────────────────────────────────────
// ZeroCopy IPC bridge — v9 rewrite
//
// Why this rewrite exists (the "GGUF problems"):
//   1. UTF-8 correctness  — llama_token_to_piece() can split one multi-byte
//      code point across two pieces. The old bridge forwarded each raw piece
//      to NewStringUTF() independently, which produced mojibake for any
//      non-ASCII output on every GGUF model. v9 assembles byte fragments and
//      only ever forwards COMPLETE UTF-8 sequences to Java.
//   2. JNI performance    — the old bridge did GetObjectClass() + GetMethodID()
//      + Attach/DetachCurrentThread() on EVERY single token. v9 resolves the
//      callback class + method IDs ONCE per inference and attaches the thread
//      at most once.
//   3. Config actually applies — temperature/top-p/min-p/top-k changes now
//      rebuild the sampler immediately (previously only repeat-penalty did, so
//      users saw "the model ignores my settings").
//   4. Thread safety       — model/context/sampler/history are guarded by a
//      mutex; unload during inference can no longer use-after-free, and
//      concurrent execute calls are serialized instead of corrupting state.
//   5. Context keeps the system prompt — when a long chat overflows n_ctx the
//      old code kept only the LAST tokens (system prompt destroyed → model
//      loses its instructions). v9 keeps the HEAD (system + template opening)
//      plus the TAIL (recent turns + assistant header) and drops the middle.
//   6. Stop-sequence scan is O(tail window) instead of O(entire response) per
//      token.
//   7. No more dead "warm-up": it pre-encoded the system prompt into the KV
//      cache that the first inference immediately wiped anyway.
//   8. The real n_ctx (after the OOM retry ladder) is tracked, so KV-usage %
//      and prompt truncation use the ACTUAL context, not the requested one.
//   9. New getNativeDiagnosticsNative() endpoint for the in-app Diagnostics
//      screen.
//
// JNI surface is byte-for-byte identical to v8 except the ADDITIVE
// getNativeDiagnosticsNative(), so the Kotlin side keeps working unchanged.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <sys/resource.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <chrono>
#include <sstream>
#include <thread>
#include <android/log.h>
#include <fstream>
#include <algorithm>

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

#define LOG_TAG "ZeroCopy_v9"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// ── Configuration ───────────────────────────────────────────────────────────

struct EngineConfig {
    int      n_ctx          = 4096;
    int      n_batch        = 512;
    int      n_threads      = 0;
    int      n_gpu_layers   = 0;
    int      max_new_tokens = 2048;
    float    temperature    = 0.5f;
    float    top_p          = 0.9f;
    float    min_p          = 0.1f;
    int      top_k          = 40;
    float    repeat_penalty = 1.1f;
    float    freq_penalty   = 0.0f;
    float    pres_penalty   = 0.1f;
    uint32_t seed           = LLAMA_DEFAULT_SEED;
    bool     low_ram_mode   = true;
    bool     flash_attn     = true;
    std::string system_prompt =
        "You are a helpful, concise assistant running on-device. "
        "Respond clearly and directly.";
    std::string mmproj_path   = "";
    std::string chat_template = "auto";
};

struct Message { std::string role; std::string content; };

// ── Engine state — every access is serialized by g_mtx ──────────────────────
static std::mutex           g_mtx;
static EngineConfig         g_cfg;
static llama_model*         g_model   = nullptr;
static llama_context*       g_ctx     = nullptr;
static llama_sampler*       g_sampler = nullptr;
static std::vector<Message> g_history;
static std::string          g_model_path = "";
static int                  g_ctx_actual = 0;   // real n_ctx after retry ladder
static bool                 g_flash_attn_effective = false;
static bool                 g_backend_initialized = false;

#ifdef ZC_HAS_CLIP
static struct clip_ctx* g_clip = nullptr;
#endif

// Abort is intentionally lock-free so the UI can always stop a run instantly,
// even while the engine mutex is held by a long generation.
static std::atomic<bool> g_abort{false};

// Big-core list — read once, immutable afterwards (no lock required).
static std::vector<int> g_big_cores;
static bool g_big_cores_cached = false;

// ── CPU topology / features ─────────────────────────────────────────────────

static std::vector<int> detect_big_cores() {
    std::vector<int> big_cores;
    std::vector<std::pair<int, int>> core_freqs;
    int ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) {
            int freq = 0;
            if (fscanf(f, "%d", &freq) == 1) core_freqs.push_back({cpu, freq});
            fclose(f);
        }
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
    if (!g_big_cores_cached) { g_big_cores = detect_big_cores(); g_big_cores_cached = true; }
    if (g_big_cores.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : g_big_cores) CPU_SET(core, &cpuset);
    pid_t tid = (pid_t)syscall(SYS_gettid);
    if (sched_setaffinity(tid, sizeof(cpuset), &cpuset) == 0)
        LOGI("Pinned to %zu big cores", g_big_cores.size());
#endif
}

static void pin_to_all_cores() {
#ifdef __aarch64__
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) CPU_SET(cpu, &cpuset);
    pid_t tid = (pid_t)syscall(SYS_gettid);
    sched_setaffinity(tid, sizeof(cpuset), &cpuset);
#endif
}

static void boost_priority() {
    // Raise the priority of THIS thread only (tid 0 = calling thread),
    // so the Compose UI compositor is never starved while we generate.
    if (setpriority(PRIO_PROCESS, 0, -8) == 0)
        LOGI("Inference thread priority set to -8");
}

// NOTE: mlockall(MCL_FUTURE) was removed — it caused persistent crash-on-launch
// on Exynos 9825 (Note 10 Lite): it locks ALL future mmap() calls into physical
// RAM, including JVM class loading on the next app launch, exhausting
// RLIMIT_MEMLOCK and making the app unlaunchable until data was cleared.
// llama.cpp's own model mmap is sufficient — no extra page locking needed.

static void apply_perf_optimizations() {
    boost_priority();
    pin_to_big_cores();
}

// Word-boundary search in the /proc/cpuinfo Features line (so "sve" doesn't
// match "sve2" and "asimddp" doesn't match "asimd").
static std::string cpu_features_line() {
    std::string feats;
#ifdef __aarch64__
    std::ifstream f("/proc/cpuinfo");
    std::string line;
    while (std::getline(f, line)) {
        if (line.find("Features") != std::string::npos) {
            size_t c = line.find(':');
            if (c != std::string::npos) feats = line.substr(c + 1);
            break;
        }
    }
#endif
    return feats;
}

static bool cpu_has_feature(const std::string& feats, const char* name) {
    size_t len = strlen(name);
    size_t pos = 0;
    while ((pos = feats.find(name, pos)) != std::string::npos) {
        bool left_ok  = (pos == 0 || feats[pos - 1] == ' ');
        bool right_ok = (pos + len >= feats.size() || feats[pos + len] == ' ');
        if (left_ok && right_ok) return true;
        pos += len;
    }
    return false;
}

// May we enable flash attention? Requires i8mm (ARMv8.4-a+) or SVE.
// Exynos 9825/9820 (ARMv8.2-a) have neither — enabling FA there crashes
// llama_init_from_model() via i8mm kernel intrinsics. Emulators/containers
// without a Features line are conservatively treated as "no".
static bool detect_i8mm() {
#ifdef __aarch64__
    std::string feats = cpu_features_line();
    if (feats.empty()) return false;
    return cpu_has_feature(feats, "i8mm")
        || cpu_has_feature(feats, "sve")
        || cpu_has_feature(feats, "sve2");
#else
    return true; // x86_64 — FA codegen is AVX-safe
#endif
}

// ── Sampler ─────────────────────────────────────────────────────────────────

// Caller must hold g_mtx. Rebuilds the sampler chain from the CURRENT config,
// so temperature/top-p/min-p/top-k/seed changes take effect immediately.
static void rebuild_sampler() {
    if (!g_ctx) return;
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Correct order: penalties -> temperature -> top-p -> min-p -> distribution
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(64, g_cfg.repeat_penalty, g_cfg.freq_penalty, g_cfg.pres_penalty));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_cfg.temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_cfg.top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_cfg.min_p, 1));
    if (g_cfg.top_k > 0) {
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(g_cfg.top_k));
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_cfg.seed));
    LOGI("Sampler rebuilt: temp=%.2f top_p=%.2f min_p=%.2f top_k=%d seed=%u",
         g_cfg.temperature, g_cfg.top_p, g_cfg.min_p, g_cfg.top_k, g_cfg.seed);
}

// ── Chat template ───────────────────────────────────────────────────────────

// Applies a chat template with a grow-until-fits buffer (no fixed 64KB cap).
// Returns "" if the template rejected this message set.
static std::string apply_chat_template(const char* tmpl, const std::vector<llama_chat_message>& msgs) {
    size_t cap = 65536;
    for (int attempt = 0; attempt < 4; attempt++) {
        std::vector<char> buf(cap);
        int n = llama_chat_apply_template(tmpl, msgs.data(), (int)msgs.size(), true, buf.data(), (int)buf.size());
        if (n < 0) return "";
        if (n < (int)buf.size()) return std::string(buf.data(), n);
        cap = (size_t)n + 1; // buffer too small — grow and retry
    }
    return "";
}

static std::string build_chat_prompt() {
    std::vector<llama_chat_message> msgs;
    if (!g_cfg.system_prompt.empty()) msgs.push_back({"system", g_cfg.system_prompt.c_str()});
    for (auto& m : g_history) msgs.push_back({m.role.c_str(), m.content.c_str()});

    // Use the user-selected template when set (not "auto"), otherwise fall
    // back to the model's built-in chat template from GGUF metadata.
    const char* tmpl = nullptr;
    if (g_cfg.chat_template != "auto") {
        tmpl = g_cfg.chat_template.c_str();
    } else {
        tmpl = g_model ? llama_model_chat_template(g_model, nullptr) : nullptr;
    }
    if (!tmpl) tmpl = "chatml";

    std::string out = apply_chat_template(tmpl, msgs);
    if (out.empty() && strcmp(tmpl, "chatml") != 0) {
        // Gemma 3 (and some other models) reject system messages — retry without.
        LOGW("Template with system prompt failed, retrying without system");
        std::vector<llama_chat_message> no_sys;
        for (auto& m : g_history) no_sys.push_back({m.role.c_str(), m.content.c_str()});
        out = apply_chat_template(tmpl, no_sys);
        if (!out.empty()) { msgs = std::move(no_sys); }
    }
    if (out.empty() && strcmp(tmpl, "chatml") != 0) {
        LOGW("Chat template detection failed, falling back to chatml");
        std::vector<llama_chat_message> no_sys;
        for (auto& m : g_history) no_sys.push_back({m.role.c_str(), m.content.c_str()});
        out = apply_chat_template("chatml", no_sys);
        if (!out.empty()) { msgs = std::move(no_sys); }
    }
    if (out.empty()) {
        // Last resort: hand-rolled ChatML.
        std::ostringstream s;
        if (!g_cfg.system_prompt.empty()) s << "<|im_start|>system\n" << g_cfg.system_prompt << "<|im_end|>\n";
        for (auto& m : g_history) s << "<|im_start|>" << m.role << "\n" << m.content << "<|im_end|>\n";
        s << "<|im_start|>assistant\n";
        out = s.str();
    }
    return out;
}

// ── Stop sequences ──────────────────────────────────────────────────────────

// Stop sequences — chat template markers that signal the model is starting
// a new turn (should stop generation and truncate at this point).
// IMPORTANT: <|im_end|> MUST be here so generation stops immediately at the
// end-of-assistant-turn token; without it the model continues and generates
// a spurious follow-up Q&A pair.
static const std::vector<std::string> g_stop_sequences = {
    "<|im_end|>",
    "<|im_start|>",
    "\n<|im_start|>user",
    "\n<|im_start|>assistant",
    "\n<|im_start|>system",
    "<|end_of_turn|>",
    "<|eot_id|>",
    "<|end|>",
    "\n<start_of_turn>user",
    "\n<start_of_turn>model",
    "\n<start_of_turn>assistant",
    "\n<|user|>",
    "\n<|assistant|>",
    "<end>",
    "</s>",
    "<im_end>",
    "[/INST]",
    "<|EOT|>",
};

// Patterns to strip from generated output before saving
static const std::vector<std::string> g_token_patterns = {
    "<|im_start|>", "<|im_end|>", "<|end|>",
    "<|user|>", "<|assistant|>", "<|system|>",
    "<end>", "<im_end>", "</s>",
};

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

static size_t g_max_stop_len = 0;

// Only the TAIL of the response can contain a NEW stop marker; scanning the
// whole buffer every token was O(n^2) on long replies. The window is padded by
// the longest stop sequence so a marker straddling the window boundary is
// still caught.
static bool contains_stop_sequence(const std::string& text, size_t* pos_out, size_t* len_out) {
    if (g_max_stop_len == 0) {
        for (const auto& s : g_stop_sequences) g_max_stop_len = std::max(g_max_stop_len, s.size());
    }
    const size_t win = 96 + g_max_stop_len;
    const size_t start = text.size() > win ? text.size() - win : 0;
    for (const auto& seq : g_stop_sequences) {
        size_t found = text.find(seq, start);
        if (found != std::string::npos) {
            *pos_out = found;
            *len_out = seq.size();
            return true;
        }
    }
    return false;
}

// ── UTF-8 assembler ─────────────────────────────────────────────────────────

// llama_token_to_piece() returns raw BYTES; a multi-byte code point can be
// split across two consecutive pieces (very common for Arabic/CJK/accents).
// Replace any invalid UTF-8 byte sequence with U+FFFD so NewStringUTF()
// never receives a malformed string — NewStringUTF ABORTS the JVM on bad
// input, and GGUF byte-fallback tokens can split UTF-8 mid-sequence (a
// lead byte followed by an ASCII byte etc.). This is the hard-crash guard.
static std::string sanitize_utf8(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char b = (unsigned char)s[i];
        if (b < 0x80) { out += (char)b; i++; continue; }
        int need;
        if      ((b & 0xE0) == 0xC0) need = 2;
        else if ((b & 0xF0) == 0xE0) need = 3;
        else if ((b & 0xF8) == 0xF0) need = 4;
        else { out += "\xEF\xBF\xBD"; i++; continue; }
        bool ok = (i + need) <= n;
        for (int k = 1; ok && k < need; k++) {
            unsigned char c = (unsigned char)s[i + k];
            if ((c & 0xC0) != 0x80) ok = false;
        }
        if (ok) { out.append(s, i, need); i += (size_t)need; }
        else    { out += "\xEF\xBF\xBD"; i++; }
    }
    return out;
}

// Feeding a split sequence to NewStringUTF() produces garbage or '?'. This
// assembler buffers fragments and only ever forwards complete code points.
struct Utf8Assembler {
    std::string pending;

    // True when the buffer ends on a UTF-8 code-point boundary.
    static bool ends_complete(const std::string& s) {
        size_t n = s.size();
        if (n == 0) return true;
        size_t j = n;
        while (j > 0 && ((unsigned char)s[j - 1] & 0xC0) == 0x80) j--; // skip continuation bytes
        if (j == n) return true;                  // last byte is ASCII or a lead
        if (j == 0) return true;                  // lone continuation bytes — flush (corrupt input)
        unsigned char lead = (unsigned char)s[j - 1];
        int need;
        if (lead < 0x80)                need = 1;
        else if ((lead & 0xE0) == 0xC0) need = 2;
        else if ((lead & 0xF0) == 0xE0) need = 3;
        else if ((lead & 0xF8) == 0xF0) need = 4;
        else return true;                         // invalid lead — flush as-is
        return (n - j + 1) >= need;
    }

    // Append a byte fragment; returns the complete prefix to forward ("" if
    // the tail is still an incomplete code point).
    std::string push(const char* bytes, int len) {
        pending.append(bytes, len);
        if (ends_complete(pending)) {
            std::string out;
            out.swap(pending);
            return out;
        }
        return "";
    }
};

// ── JNI callback context ────────────────────────────────────────────────────

// Resolves the Java callback's class + method IDs ONCE per inference and
// attaches the calling thread at most once — the v8 code did this on every
// token, which was the single biggest JNI overhead in the hot path.
struct JniCb {
    JNIEnv*   env = nullptr;
    bool      attached = false;
    jobject   global = nullptr;
    jclass    cls = nullptr;
    jmethodID onToken  = nullptr;
    jmethodID onDone   = nullptr;
    jmethodID onError  = nullptr;
    jmethodID onKv     = nullptr;
    jmethodID onTokens = nullptr;

    bool init(jobject java_cb) {
        if (!g_jvm || !java_cb) return false;
        int stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (stat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
            attached = true;
        } else if (stat != JNI_OK || !env) {
            return false;
        }
        global = env->NewGlobalRef(java_cb);
        jclass raw = env->GetObjectClass(java_cb);
        if (!raw) return false;
        cls = (jclass)env->NewGlobalRef(raw);
        env->DeleteLocalRef(raw);
        onToken  = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        onDone   = env->GetMethodID(cls, "onDone", "()V");
        onError  = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
        onKv     = env->GetMethodID(cls, "onKvCacheUsage", "(I)V");
        onTokens = env->GetMethodID(cls, "onTokensGenerated", "(I)V");
        return onToken && onDone && onError;
    }

    void token(const std::string& t) {
        if (env && global && onToken) {
            jstring s = env->NewStringUTF(sanitize_utf8(t).c_str());
            env->CallVoidMethod(global, onToken, s);
            env->DeleteLocalRef(s);
        }
    }
    void done()  { if (env && global && onDone)  env->CallVoidMethod(global, onDone); }
    void error(const std::string& e) {
        if (env && global && onError) {
            jstring s = env->NewStringUTF(sanitize_utf8(e).c_str());
            env->CallVoidMethod(global, onError, s);
            env->DeleteLocalRef(s);
        }
    }
    void kv(int pct)     { if (env && global && onKv)     env->CallVoidMethod(global, onKv, pct); }
    void tokens(int cnt) { if (env && global && onTokens) env->CallVoidMethod(global, onTokens, cnt); }

    void destroy() {
        if (env) {
            if (cls)    env->DeleteGlobalRef(cls);
            if (global) env->DeleteGlobalRef(global);
        }
        if (attached && g_jvm) g_jvm->DetachCurrentThread();
        env = nullptr; cls = nullptr; global = nullptr; attached = false;
    }
};

// ── KV cache helpers ────────────────────────────────────────────────────────

static llama_memory_t get_mem() { return llama_get_memory(g_ctx); }

// KV-cache usage as a percentage of the ACTUAL context (post retry ladder).
// Returns -1 for SSM/recurrent models (Mamba) where the concept doesn't apply.
static int kv_cache_usage_pct() {
    if (!g_ctx || g_ctx_actual <= 0) return 0;
    if (g_model) {
        char arch[64] = {0};
        if (llama_model_meta_val_str(g_model, "general.architecture", arch, sizeof(arch)) >= 0 &&
            (strcmp(arch, "mamba") == 0 || strcmp(arch, "mamba2") == 0)) {
            return -1;
        }
    }
    llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
    int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
    return (int)((used * 100LL) / g_ctx_actual);
}

// Caller holds g_mtx. When a long conversation overflows the context, keep the
// HEAD (system prompt + template opening) and the TAIL (recent turns + the
// assistant header that anchors generation); drop only the middle.
static void truncate_prompt(std::vector<llama_token>& tokens, int limit) {
    if ((int)tokens.size() <= limit) return;
    int keep = std::min(256, (int)tokens.size() / 5);
    int tail = limit - keep;
    if (tail < 64) { keep = std::max(0, limit - 64); tail = limit - keep; }
    if (tail <= 0) { // pathological tiny context — just keep the tail
        std::vector<llama_token> t(tokens.end() - limit, tokens.end());
        tokens.swap(t);
        return;
    }
    std::vector<llama_token> trimmed;
    trimmed.reserve(limit);
    trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + keep);
    trimmed.insert(trimmed.end(), tokens.end() - tail, tokens.end());
    tokens.swap(trimmed);
    LOGW("Prompt truncated: kept head=%d tail=%d (limit=%d)", keep, tail, limit);
}

// ── Shared generation loop (text + vision) ──────────────────────────────────

// Runs the sample/decode loop. Caller holds g_mtx and has already decoded the
// prompt; `cb` is initialized. Returns the number of tokens generated.
static int generate_loop(JniCb& cb, Utf8Assembler& utf8, std::string& response) {
    pin_to_big_cores();
    int tokens_generated = 0;
    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_abort.load()) { LOGI("Aborted at token %d", i); break; }

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) break;

        char piece[256];
        int n = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            std::string chunk;
            if (n < (int)sizeof(piece)) {
                chunk.assign(piece, n);
            } else {
                std::vector<char> buf(n + 1);
                llama_token_to_piece(llama_model_get_vocab(g_model), tok, buf.data(), buf.size(), 0, false);
                chunk.assign(buf.data(), n);
            }

            tokens_generated = i + 1;
            cb.tokens(tokens_generated);

            // Forward only COMPLETE UTF-8 code points (fixes mojibake).
            std::string complete = utf8.push(chunk.data(), (int)chunk.size());
            if (!complete.empty()) {
                response += complete;
                cb.token(complete);
            }

            // Stop if the model starts a new chat turn (tail-window scan).
            size_t sp = 0, sl = 0;
            if (contains_stop_sequence(response, &sp, &sl)) {
                response = response.substr(0, sp);
                LOGI("Stop sequence hit at byte %zu (len=%zu)", sp, sl);
                break;
            }
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, nb) != 0) {
            LOGW("Decode failed at token %d", i);
            break;
        }

        if ((i & 0xF) == 0) cb.kv(kv_cache_usage_pct());
    }
    // Flush any trailing partial code point (rare; only on hard stop).
    if (!utf8.pending.empty()) {
        response += utf8.pending;
        cb.token(utf8.pending);
        utf8.pending.clear();
    }
    return tokens_generated;
}

// ── JNI: configuration ──────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setEngineConfigNative(
        JNIEnv*, jobject,
        jint nCtx, jint nBatch, jint maxNewTokens, jfloat temp,
        jfloat topP, jfloat minP, jint topK, jint nGpuLayers, jint nThreads, jint seed,
        jboolean lowRamMode, jboolean flashAttention) {
    std::lock_guard<std::mutex> lock(g_mtx);
    g_cfg.n_ctx          = (nCtx > 0) ? nCtx : 4096;
    g_cfg.n_batch        = (nBatch > 0) ? nBatch : 2048;
    g_cfg.max_new_tokens = (maxNewTokens > 0) ? maxNewTokens : 2048;
    g_cfg.temperature    = (temp > 0.0f) ? temp : 0.5f;
    g_cfg.top_p          = topP;
    g_cfg.min_p          = minP;
    g_cfg.top_k          = topK;
    g_cfg.n_gpu_layers   = nGpuLayers;
    g_cfg.n_threads      = nThreads;
    g_cfg.seed           = (seed < 0) ? LLAMA_DEFAULT_SEED : (uint32_t)seed;
    g_cfg.low_ram_mode   = lowRamMode;
    g_cfg.flash_attn     = flashAttention;
    LOGI("Config: ctx=%d batch=%d gpu=%d threads=%d lowRam=%d flashAttn=%d topK=%d",
         nCtx, nBatch, nGpuLayers, nThreads, (int)lowRamMode, (int)flashAttention, topK);
    // Sampler changes take effect immediately, not only after a reload.
    rebuild_sampler();
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setRepeatPenaltyNative(
        JNIEnv*, jobject,
        jfloat repeatPenalty, jfloat freqPenalty, jfloat presPenalty) {
    std::lock_guard<std::mutex> lock(g_mtx);
    g_cfg.repeat_penalty = repeatPenalty;
    g_cfg.freq_penalty   = freqPenalty;
    g_cfg.pres_penalty   = presPenalty;
    rebuild_sampler();
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setSystemPromptNative(
        JNIEnv* env, jobject, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (!s) return;
    std::lock_guard<std::mutex> lock(g_mtx);
    g_cfg.system_prompt = s;
    env->ReleaseStringUTFChars(prompt, s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_setChatTemplateNative(
        JNIEnv* env, jobject, jstring template_) {
    const char* t = env->GetStringUTFChars(template_, nullptr);
    if (!t) return;
    std::lock_guard<std::mutex> lock(g_mtx);
    g_cfg.chat_template = t;
    env->ReleaseStringUTFChars(template_, t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_resetContextNative(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    g_history.clear();
    if (g_ctx) llama_memory_clear(get_mem(), true);
    LOGI("Context reset");
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_unloadModelNative(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    LOGI("Unloading native model");
    g_history.clear();
    g_abort.store(false);
#ifdef ZC_HAS_CLIP
    if (g_clip)   { clip_free(g_clip);   g_clip = nullptr; }
#endif
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_ctx_actual = 0;
    g_flash_attn_effective = false;
    g_model_path = "";
    g_cfg.mmproj_path = "";
    LOGI("Native model unloaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_abortInferenceNative(
        JNIEnv*, jobject) {
    // Lock-free on purpose: must work even while the engine mutex is held by
    // a long generation.
    g_abort.store(true);
    LOGI("Inference abort requested");
}

// ── JNI: model loading ──────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_loadGgufModelNative(
        JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;
    std::string path_copy(filePath);
    env->ReleaseStringUTFChars(path, filePath);

    std::lock_guard<std::mutex> lock(g_mtx);

    // llama_backend_init() must be called exactly once per process lifetime —
    // NOT reset on failures (a second call is undefined behaviour).
    if (!g_backend_initialized) { llama_backend_init(); g_backend_initialized = true; }

#ifdef ZC_HAS_CLIP
    if (g_clip)   { clip_free(g_clip);              g_clip   = nullptr; }
#endif
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();
    g_ctx_actual = 0;
    g_flash_attn_effective = false;
    g_model_path = "";

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_cfg.n_gpu_layers;

    g_model = llama_model_load_from_file(path_copy.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load model: %s", path_copy.c_str());
        return JNI_FALSE;
    }
    g_model_path = path_copy;

    int total_cores = (int)std::thread::hardware_concurrency();
    if (total_cores < 1) total_cores = 4;
    int n_threads = (g_cfg.n_threads > 0) ? std::min(g_cfg.n_threads, total_cores) : total_cores;

    // Only enable flash attention on chips that actually have i8mm (ARMv8.4-a+:
    // Exynos 2200, Snapdragon 888+, …). Exynos 9825 (ARMv8.2-a) has no i8mm →
    // FA disabled, otherwise llama_init_from_model() crashes.
    bool use_flash_attn = g_cfg.flash_attn && detect_i8mm();
    LOGI("CPU i8mm=%d, flash_attn requested=%d, effective=%d",
         (int)detect_i8mm(), (int)g_cfg.flash_attn, (int)use_flash_attn);

    // Low RAM mode: reduce n_ctx aggressively.
    int n_ctx = g_cfg.n_ctx;
    if (g_cfg.low_ram_mode) n_ctx = std::min(n_ctx, 2048);
    LOGI("Low RAM mode: n_ctx limited to %d", n_ctx);

    // n_batch must not exceed n_ctx; n_ubatch must not exceed n_batch.
    int n_batch  = std::min(g_cfg.n_batch, n_ctx);
    int n_ubatch = std::min(512, n_batch);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = n_batch;
    cparams.n_ubatch        = n_ubatch;
    cparams.n_threads       = std::max(1, n_threads / 2);
    cparams.n_threads_batch = n_threads;
    // b9581 uses flash_attn_type enum.
    cparams.flash_attn_type = use_flash_attn
                              ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                              : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    g_ctx = llama_init_from_model(g_model, cparams);
    int chosen = n_ctx;
    // OOM retry ladder: 1024, then 512, before giving up.
    const int ladder[] = {1024, 512};
    for (int fallback : ladder) {
        if (g_ctx) break;
        if (fallback >= n_ctx) continue;
        LOGW("Context failed at n_ctx=%d, retrying with %d", chosen, fallback);
        cparams.n_ctx    = fallback;
        cparams.n_batch  = std::min(n_batch, fallback);
        cparams.n_ubatch = std::min(n_ubatch, fallback / 2);
        g_ctx = llama_init_from_model(g_model, cparams);
        chosen = fallback;
    }
    g_ctx_actual = g_ctx ? chosen : 0;
    g_flash_attn_effective = g_ctx ? use_flash_attn : false;

    if (!g_ctx) {
        // Do NOT reset g_backend_initialized — the backend is still valid.
        llama_model_free(g_model); g_model = nullptr;
        g_model_path = "";
        LOGE("Failed to create context even at n_ctx=512");
        return JNI_FALSE;
    }

    rebuild_sampler();
    apply_perf_optimizations();

    LOGI("Model loaded: ctx=%d batch=%d ubatch=%d gpu=%d threads=%d cores=%d lowRam=%d fa=%d",
         cparams.n_ctx, cparams.n_batch, cparams.n_ubatch,
         g_cfg.n_gpu_layers, n_threads, total_cores,
         (int)g_cfg.low_ram_mode, (int)use_flash_attn);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_loadMmprojNative(
        JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    std::string path_copy(p);
    env->ReleaseStringUTFChars(path, p);

    std::lock_guard<std::mutex> lock(g_mtx);
#ifdef ZC_HAS_CLIP
    if (g_clip) { clip_free(g_clip); g_clip = nullptr; }

    // clip_init (b9581+ API) instead of the deprecated clip_model_load.
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
        LOGE("Failed to load mmproj: %s", path_copy.c_str());
        return JNI_FALSE;
    }
    g_clip = init_res.ctx_v;
    g_cfg.mmproj_path = path_copy;
    LOGI("mmproj loaded successfully");
    return JNI_TRUE;
#else
    LOGE("mmproj loading not available — rebuild with CLIP support");
    return JNI_FALSE;
#endif
}

// ── JNI: model info / benchmark / export ────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_getModelInfoNative(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model) return env->NewStringUTF("{}");
    char arch[128] = "unknown";
    char key[256];
    char val[256];
    std::ostringstream j;
    j << "{";
    if (llama_model_meta_val_str(g_model, "general.architecture", arch, sizeof(arch)) >= 0) {
        j << "\"arch\":\"" << arch << "\",";
    } else {
        j << "\"arch\":\"unknown\",";
    }
    j << "\"n_params\":" << llama_model_n_params(g_model) << ",";
    j << "\"n_embd\":" << llama_model_n_embd(g_model) << ",";
    // GGUF metadata keys are prefixed with the architecture name.
    snprintf(key, sizeof(key), "%s.block_count", arch);
    if (llama_model_meta_val_str(g_model, key, val, sizeof(val)) >= 0)
        j << "\"n_layer\":" << atoi(val) << ",";
    j << "\"ctx_train\":" << llama_model_n_ctx_train(g_model) << ",";
    if (llama_model_meta_val_str(g_model, "general.file_type", val, sizeof(val)) >= 0) {
        const char* quant = "";
        int ft = atoi(val);
        switch (ft) {
            case 1: quant = "Q4_0"; break; case 2: quant = "Q4_1"; break;
            case 3: quant = "Q5_0"; break; case 4: quant = "Q5_1"; break;
            case 6: quant = "Q4_K_M"; break; case 7: quant = "Q5_K_M"; break;
            case 8: quant = "Q6_K"; break; case 9: quant = "Q8_0"; break;
            case 10: quant = "F16"; break; case 11: quant = "F32"; break;
            default: quant = val; break;
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
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"no model\"}");

    int pp_n = std::min(ppTokens, 4096);
    int tg_n = std::min(tgTokens, 2048);
    const char* test_str = "The quick brown fox jumps over the lazy dog. ";
    std::vector<llama_token> pp_toks(pp_n);
    int n = llama_tokenize(llama_model_get_vocab(g_model), test_str, strlen(test_str), pp_toks.data(), pp_n, false, true);
    if (n <= 0) n = 1;
    pp_toks.resize(n);
    while ((int)pp_toks.size() < pp_n) {
        auto old = pp_toks;
        for (auto t : old) { pp_toks.push_back(t); if ((int)pp_toks.size() >= pp_n) break; }
    }
    pp_toks.resize(pp_n);

    llama_memory_clear(get_mem(), true);
    llama_batch batch = llama_batch_get_one(pp_toks.data(), pp_n);
    auto pp_start = std::chrono::high_resolution_clock::now();
    llama_decode(g_ctx, batch);
    auto pp_end = std::chrono::high_resolution_clock::now();
    double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    double pp_tps = pp_n / (pp_ms / 1000.0);

    llama_sampler* bench_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(bench_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    llama_token token = llama_sampler_sample(bench_sampler, g_ctx, -1);
    auto tg_start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < tg_n; i++) {
        llama_batch tb = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, tb) != 0) break;
        token = llama_sampler_sample(bench_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) break;
    }
    auto tg_end = std::chrono::high_resolution_clock::now();
    double tg_ms  = std::chrono::duration<double, std::milli>(tg_end - tg_start).count();
    double tg_tps = tg_n / (tg_ms / 1000.0);
    llama_sampler_free(bench_sampler);
    llama_memory_clear(get_mem(), true);

    char result[256];
    snprintf(result, sizeof(result), "{\"pp_tps\":%.1f,\"tg_tps\":%.1f,\"pp_ms\":%.1f,\"tg_ms\":%.1f}",
             pp_tps, tg_tps, pp_ms, tg_ms);
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_exportChatHistoryNative(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    std::ostringstream out;
    out << "=== ZeroCopy Chat Export ===\n";
    for (size_t i = 0; i < g_history.size(); i++)
        out << "\n[" << (i + 1) << "] " << g_history[i].role << ":\n" << g_history[i].content << "\n";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_getKvCacheUsageNative(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    return kv_cache_usage_pct();
}

// ── JNI: history / template utilities ───────────────────────────────────────

// Robust JSON string extraction: finds {"role":"...","content":"..."} objects
// by tracking brace depth so content containing "}" (code, JSON, etc.) doesn't
// truncate the object, and unescapes \n \r \t.
static void parse_message_objects(const std::string& input, std::vector<llama_chat_message>& out) {
    size_t pos = 0;
    size_t len = input.size();
    while (pos < len) {
        pos = input.find('{', pos);
        if (pos == std::string::npos) break;

        int depth = 0;
        bool in_string = false;
        bool escaped = false;
        size_t obj_end = std::string::npos;
        for (size_t i = pos; i < len; i++) {
            char c = input[i];
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && in_string) { escaped = true; continue; }
            if (c == '"') { in_string = !in_string; continue; }
            if (in_string) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { obj_end = i; break; } }
        }
        if (obj_end == std::string::npos) break;
        std::string obj = input.substr(pos, obj_end - pos + 1);
        pos = obj_end + 1;

        auto extract_str = [&](const std::string& key) -> std::string {
            std::string needle = "\"" + key + "\":\"";
            size_t k = obj.find(needle);
            if (k == std::string::npos) return "";
            size_t vs = k + needle.size();
            std::string val;
            bool esc = false;
            for (size_t i = vs; i < obj.size(); i++) {
                char c = obj[i];
                if (esc) {
                    if (c == 'n') val += '\n';
                    else if (c == 'r') val += '\r';
                    else if (c == 't') val += '\t';
                    else val += c;
                    esc = false;
                    continue;
                }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') break;
                val += c;
            }
            return val;
        };

        std::string role    = extract_str("role");
        std::string content = extract_str("content");
        if (!role.empty() && !content.empty()) {
            out.push_back({role.c_str(), content.c_str()});
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_restoreHistoryNative(
        JNIEnv* env, jobject, jstring messagesJson) {
    const char* json = env->GetStringUTFChars(messagesJson, nullptr);
    if (!json) return;
    std::string input(json);
    env->ReleaseStringUTFChars(messagesJson, json);

    std::lock_guard<std::mutex> lock(g_mtx);
    g_history.clear();
    std::vector<llama_chat_message> parsed;
    parse_message_objects(input, parsed);
    for (auto& m : parsed) g_history.push_back({m.role, m.content});
    LOGI("Restored %zu history messages", g_history.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_formatWithChatTemplateNative(
        JNIEnv* env, jobject, jstring messagesJson) {
    const char* json = env->GetStringUTFChars(messagesJson, nullptr);
    if (!json) return env->NewStringUTF("");
    std::string input(json);
    env->ReleaseStringUTFChars(messagesJson, json);

    std::lock_guard<std::mutex> lock(g_mtx);
    std::vector<llama_chat_message> msgs;
    parse_message_objects(input, msgs);

    std::string result;
    if (g_model && !msgs.empty()) {
        const char* tmpl = llama_model_chat_template(g_model, nullptr);
        if (!tmpl) tmpl = "chatml";
        result = apply_chat_template(tmpl, msgs);
    }
    if (result.empty()) {
        // Fallback: hand-rolled ChatML (works with or without a model).
        std::ostringstream s;
        s << "<|im_start|>system\nYou are a helpful AI assistant<|im_end|>\n";
        for (auto& m : msgs) {
            s << "<|im_start|>" << m.role << "\n" << m.content << "<|im_end|>\n";
        }
        s << "<|im_start|>assistant\n";
        result = s.str();
    }
    return env->NewStringUTF(result.c_str());
}

// ── JNI: inference (text) ───────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_executeWithCallbackNative(
        JNIEnv* env, jobject, jstring jprompt, jobject callback) {
    LOGI("executeWithCallbackNative called");

    // Held for the whole generation: serializes load/unload/config and makes
    // concurrent execute calls queue up instead of corrupting shared state.
    // abortInferenceNative() stays lock-free (atomic flag).
    std::lock_guard<std::mutex> lock(g_mtx);

    JniCb cb;
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Engine not ready: model=%p ctx=%p sampler=%p", (void*)g_model, (void*)g_ctx, (void*)g_sampler);
        if (cb.init(callback)) { cb.error("Engine not ready — load a model first"); cb.destroy(); }
        return;
    }
    if (!cb.init(callback)) {
        LOGE("Failed to bind Java callback");
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    if (!user_input) { cb.error("Failed to read prompt"); cb.destroy(); return; }
    std::string user_copy(user_input);
    env->ReleaseStringUTFChars(jprompt, user_input);

    g_abort.store(false);
    g_history.push_back({"user", user_copy});
    std::string prompt = build_chat_prompt();
    LOGI("Prompt len=%zu", prompt.size());
    // ── DEBUG: dump the FINAL prompt actually handed to llama_decode ──
    // Lets us confirm whether stray think/tool preamble text survives when
    // the reasoning/search toggles are OFF. logcat truncates ~4KB, so we log
    // a generous prefix that always contains the system prompt + preamble.
    {
        bool has_think = prompt.find("<think") != std::string::npos;
        bool has_tool  = prompt.find("<tool")  != std::string::npos;
        size_t head = prompt.size() < 3500 ? prompt.size() : 3500;
        LOGI("PROMPT_DUMP think=%d tool=%d head(%zu)=\"%.*s\"",
             has_think ? 1 : 0, has_tool ? 1 : 0, head, (int)head, prompt.c_str());
    }

    // Tokenize the template-formatted prompt. add_special=false is required:
    // the template already ends with an assistant header that means "start
    // generating"; appending EOS here would make the model emit nothing.
    // parse_special=true tokenizes <|im_start|> etc. as single token IDs.
    // Some GGUFs advertise a 1M+ context in metadata — clamp the tokenize
    // buffer so we never allocate a giant vector for a long chat.
    int n_max = (int)llama_model_n_ctx_train(g_model);
    if (n_max <= 0) n_max = 2048;
    if (n_max > 262144) n_max = 262144;
    std::vector<llama_token> tokens(n_max + 64);
    int n_toks = llama_tokenize(llama_model_get_vocab(g_model), prompt.c_str(), prompt.size(),
                                tokens.data(), (int)tokens.size(), false, true);
    if (n_toks <= 0) {
        LOGE("Tokenization returned %d tokens, prompt len=%zu", n_toks, prompt.size());
        cb.error("Tokenization failed — the prompt may be too long");
        cb.destroy();
        g_history.pop_back();
        return;
    }
    tokens.resize(n_toks);

    // Fit into the ACTUAL context (post retry ladder), keeping the system
    // prompt + recent turns instead of blindly keeping the last tokens.
    int limit = std::max(64, g_ctx_actual - 2);
    truncate_prompt(tokens, limit);

    // The prompt contains the FULL conversation (already formatted by Kotlin),
    // so clear the KV cache to avoid position collision with cached tokens.
    llama_memory_clear(get_mem(), true);

    // Manually prepend BOS on the first decode (add_special=false skips it).
    llama_token bos = llama_vocab_bos(llama_model_get_vocab(g_model));
    if (bos != LLAMA_TOKEN_NULL) tokens.insert(tokens.begin(), bos);

    pin_to_all_cores();
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt decode failed for %d tokens", (int)tokens.size());
        cb.error("Prompt decode failed — reduce the context size or use a smaller model");
        cb.destroy();
        g_history.pop_back();
        return;
    }

    cb.kv(kv_cache_usage_pct());

    std::string response;
    Utf8Assembler utf8;
    int tokens_generated = generate_loop(cb, utf8, response);

    g_history.push_back({"assistant", strip_special_tokens(response)});
    cb.done();
    cb.destroy();
    LOGI("Inference done: tokens=%d chars=%zu", tokens_generated, response.size());
}

// ── JNI: inference (vision) ─────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_executeWithImageNative(
        JNIEnv* env, jobject, jstring jprompt, jstring jimagePath, jobject callback) {
    LOGI("executeWithImageNative called");
    std::lock_guard<std::mutex> lock(g_mtx);

    JniCb cb;
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Engine not ready for image inference");
        if (cb.init(callback)) { cb.error("Engine not ready — load a model first"); cb.destroy(); }
        return;
    }
    if (!cb.init(callback)) { LOGE("Failed to bind Java callback"); return; }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    const char* image_path = env->GetStringUTFChars(jimagePath, nullptr);
    if (!user_input || !image_path) {
        cb.error("Failed to read prompt/image");
        if (user_input) env->ReleaseStringUTFChars(jprompt, user_input);
        if (image_path) env->ReleaseStringUTFChars(jimagePath, image_path);
        cb.destroy();
        return;
    }
    std::string user_copy(user_input);
    std::string image_copy(image_path);
    env->ReleaseStringUTFChars(jprompt, user_input);
    env->ReleaseStringUTFChars(jimagePath, image_path);

    g_abort.store(false);
    g_history.push_back({"user", user_copy});
    std::string prompt = build_chat_prompt();
    LOGI("Image-prompt len=%zu image=%s", prompt.size(), image_copy.c_str());
    // ── DEBUG: same final-prompt dump as the text path ──
    {
        bool has_think = prompt.find("<think") != std::string::npos;
        bool has_tool  = prompt.find("<tool")  != std::string::npos;
        size_t head = prompt.size() < 3500 ? prompt.size() : 3500;
        LOGI("PROMPT_DUMP think=%d tool=%d head(%zu)=\"%.*s\"",
             has_think ? 1 : 0, has_tool ? 1 : 0, head, (int)head, prompt.c_str());
    }

    int n_max = (int)llama_model_n_ctx_train(g_model);
    if (n_max <= 0) n_max = 2048;
    if (n_max > 262144) n_max = 262144;
    std::vector<llama_token> tokens(n_max + 64);
    int n_toks = llama_tokenize(llama_model_get_vocab(g_model), prompt.c_str(), prompt.size(),
                                tokens.data(), (int)tokens.size(), false, true);
    if (n_toks <= 0) {
        LOGE("Image tokenization returned %d tokens", n_toks);
        cb.error("Tokenization failed");
        cb.destroy();
        g_history.pop_back();
        return;
    }
    tokens.resize(n_toks);

    int limit = std::max(64, g_ctx_actual - 2);
    truncate_prompt(tokens, limit);

    llama_memory_clear(get_mem(), true);

    llama_token bos = llama_vocab_bos(llama_model_get_vocab(g_model));
    if (bos != LLAMA_TOKEN_NULL) tokens.insert(tokens.begin(), bos);

    // Process image through CLIP if available (b9581+ API).
    std::vector<float> image_embeds;
    int n_image_tokens = 0;
#ifdef ZC_HAS_CLIP
    if (g_clip) {
        int n_threads = g_cfg.n_threads > 0 ? g_cfg.n_threads : 4;

        int img_width = 0, img_height = 0, img_channels = 0;
        unsigned char* img_data = stbi_load(image_copy.c_str(), &img_width, &img_height, &img_channels, 3);
        if (img_data && img_width > 0 && img_height > 0) {
            size_t n_pixels = (size_t)img_width * (size_t)img_height;
            std::vector<float> float_pixels(n_pixels * 3);
            for (size_t i = 0; i < n_pixels; i++) {
                float_pixels[i * 3 + 0] = img_data[i * 3 + 0] / 255.0f;
                float_pixels[i * 3 + 1] = img_data[i * 3 + 1] / 255.0f;
                float_pixels[i * 3 + 2] = img_data[i * 3 + 2] / 255.0f;
            }
            stbi_image_free(img_data);

            struct clip_image_f32* clip_img = clip_image_f32_init();
            if (clip_img) {
                clip_img->set_size({img_width, img_height}, false, false);
                clip_img->cpy_buf(float_pixels);

                n_image_tokens = clip_n_output_tokens(g_clip, clip_img);
                if (n_image_tokens < 1) n_image_tokens = 256; // safety fallback

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
            LOGW("Failed to load image: %s", image_copy.c_str());
        }
    } else {
        LOGW("No mmproj loaded, skipping image processing");
    }
#else
    LOGW("CLIP not available in this build, skipping image processing");
#endif

    // Context shift if the image + prompt will overflow the context.
    llama_pos cur_max = llama_memory_seq_pos_max(get_mem(), 0);
    int n_ctx_used = (cur_max >= 0) ? (int)(cur_max + 1) : 0;
    int total_needed = n_ctx_used + n_image_tokens + (int)tokens.size() + 128;
    if (total_needed >= g_ctx_actual) {
        int keep = g_ctx_actual / 4;
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

    // Inject image embeddings as a separate decode step before text tokens.
    if (!image_embeds.empty() && n_image_tokens > 0) {
        int n_embd = llama_model_n_embd(g_model);
        LOGI("Processing multimodal input: %d image + %d text tokens", n_image_tokens, n_text_toks);

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

        llama_batch ib;
        ib.n_tokens = n_image_tokens;
        ib.token    = img_tokens.data();
        ib.embd     = image_embeds.data();
        ib.pos      = img_pos.data();
        ib.n_seq_id = img_n_seq_id.data();
        ib.seq_id   = img_seq_id.data();
        ib.logits   = img_logits.data();

        if (llama_decode(g_ctx, ib) != 0) {
            cb.error("Image embedding decode failed");
            cb.destroy();
            g_history.pop_back();
            return;
        }

        // Text tokens (including BOS) follow the image tokens.
        std::vector<int32_t> text_pos(n_text_toks);
        std::vector<int32_t> text_n_seq_id(n_text_toks, 1);
        std::vector<llama_seq_id*> text_seq_id(n_text_toks);
        std::vector<llama_seq_id> text_seq_id_data(n_text_toks, 0);
        std::vector<int8_t> text_logits(n_text_toks, 0);
        for (int i = 0; i < n_text_toks; i++) {
            text_pos[i] = n_image_tokens + i;
            text_seq_id[i] = &text_seq_id_data[i];
        }
        // We only need logits for the LAST prompt token (to sample the first
        // generated token from); without this llama_decode computes no logits
        // and llama_sampler_sample() reads stale state → empty/garbage output.
        if (n_text_toks > 0) text_logits[n_text_toks - 1] = 1;

        llama_batch tb;
        tb.n_tokens = n_text_toks;
        tb.token    = tokens.data();
        tb.embd     = nullptr;
        tb.pos      = text_pos.data();
        tb.n_seq_id = text_n_seq_id.data();
        tb.seq_id   = text_seq_id.data();
        tb.logits   = text_logits.data();

        if (llama_decode(g_ctx, tb) != 0) {
            cb.error("Text prompt decode after image failed");
            cb.destroy();
            g_history.pop_back();
            return;
        }
    } else {
        llama_batch batch = llama_batch_get_one(tokens.data(), n_text_toks);
        if (llama_decode(g_ctx, batch) != 0) {
            cb.error("Prompt decode failed");
            cb.destroy();
            g_history.pop_back();
            return;
        }
    }

    cb.kv(kv_cache_usage_pct());

    std::string response;
    Utf8Assembler utf8;
    int tokens_generated = generate_loop(cb, utf8, response);

    g_history.push_back({"assistant", strip_special_tokens(response)});
    cb.done();
    cb.destroy();
    LOGI("Image inference done: tokens=%d chars=%zu", tokens_generated, response.size());
}

// ── JNI: native diagnostics (for the in-app Diagnostics screen) ─────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_zerocopy_domain_inference_NativeBridge_getNativeDiagnosticsNative(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    std::ostringstream j;
    j << "{";
    j << "\"bridge\":\"v9\",";
#ifdef ZC_ARCH_PROFILE_STR
    j << "\"arch_profile\":\"" << ZC_ARCH_PROFILE_STR << "\",";
#else
    j << "\"arch_profile\":\"unknown\",";
#endif
    std::string feats = cpu_features_line();
    {
        // trim leading/trailing whitespace
        size_t a = feats.find_first_not_of(" \t\r\n");
        size_t b = feats.find_last_not_of(" \t\r\n");
        if (a == std::string::npos) feats.clear(); else feats = feats.substr(a, b - a + 1);
    }
    j << "\"cpu_features\":\"" << feats << "\",";
    int ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu < 1) ncpu = 0;
    j << "\"cores\":" << ncpu << ",";
    if (g_big_cores_cached) j << "\"big_cores\":" << (int)g_big_cores.size() << ",";
    j << "\"model_loaded\":" << (g_model ? "true" : "false") << ",";
    if (g_model) {
        j << "\"n_params\":" << llama_model_n_params(g_model) << ",";
        j << "\"n_ctx\":" << g_ctx_actual << ",";
        j << "\"flash_attn\":" << (g_flash_attn_effective ? "true" : "false") << ",";
        j << "\"model_path\":\"" << g_model_path << "\",";
    }
    long total_ram_kb = 0;
    {
        std::ifstream m("/proc/meminfo");
        std::string line;
        if (std::getline(m, line)) {
            char key[64];
            long val = 0;
            if (sscanf(line.c_str(), "%63s %ld", key, &val) == 2) total_ram_kb = val;
        }
    }
    j << "\"ram_mb\":" << (total_ram_kb / 1024);
    j << "}";
    return env->NewStringUTF(j.str().c_str());
}

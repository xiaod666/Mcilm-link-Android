#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>

#define TAG "MciLmBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef NDEBUG
#define SAFE_EXCEPTION_CHECK(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionClear(); } } while(0)
#else
#define SAFE_EXCEPTION_CHECK(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); } } while(0)
#endif

typedef char* (*fn_version)();
typedef int   (*fn_running)();
typedef int   (*fn_host_start)(const char*, const char*, const char*, const char*, const char*, const char*, const char*, int);
typedef int   (*fn_client_start)(const char*, const char*, const char*, const char*, const char*, const char*, int, int, int);
typedef char* (*fn_query)(const char*);
typedef char* (*fn_command)(const char*);
typedef void  (*fn_stop)();
typedef void  (*fn_free_string)(char*);
typedef void  (*fn_set_log)(void (*cb)(const char*, void*), void*);
typedef void  (*fn_set_event)(void (*cb)(const char*, const char*, void*), void*);
typedef void  (*fn_set_discovery)(const char*, int);
typedef int   (*fn_set_extra)(const char*);
typedef void  (*fn_set_audio)(int (*)(char*, int, void*), int (*)(char*, int, void*), void*);

static void* g_lib_handle = nullptr;
static fn_version g_version = nullptr;
static fn_running g_running = nullptr;
static fn_host_start g_host_start = nullptr;
static fn_client_start g_client_start = nullptr;
static fn_query g_query = nullptr;
static fn_command g_command = nullptr;
static fn_stop g_stop = nullptr;
static fn_free_string g_free_string = nullptr;
static fn_set_log g_set_log = nullptr;
static fn_set_event g_set_event = nullptr;
static fn_set_discovery g_set_discovery = nullptr;
static fn_set_extra g_set_extra = nullptr;
static fn_set_audio g_set_audio = nullptr;

static JavaVM* g_vm = nullptr;
static jclass g_native_class = nullptr;
static jmethodID g_on_log_mid = nullptr;
static jmethodID g_on_event_mid = nullptr;
static jmethodID g_on_audio_read_mid = nullptr;
static jmethodID g_on_audio_write_mid = nullptr;

struct ScopedEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    ScopedEnv() {
        if (!g_vm) return;
        jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (r == JNI_OK) return;
        if (r == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
            else env = nullptr;
            return;
        }
        env = nullptr;
    }
    ~ScopedEnv() { if (attached && g_vm) g_vm->DetachCurrentThread(); }
};

/* ---------- UTF-8 <-> UTF-16 转换（绕开 JNI modified UTF-8 限制） ---------- */

/* 标准 UTF-8 -> jstring（支持 4 字节 emoji；非法字节用 U+FFFD 替换） */
static jstring utf8_to_jstring(JNIEnv* env, const char* s) {
    if (!s) return env->NewString(nullptr, 0);
    size_t n = strlen(s);
    std::vector<jchar> buf;
    buf.reserve(n);
    size_t i = 0;
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        uint32_t cp; int len;
        if (c < 0x80)              { cp = c;         len = 1; }
        else if ((c & 0xE0)==0xC0) { cp = c & 0x1F;  len = 2; }
        else if ((c & 0xF0)==0xE0) { cp = c & 0x0F;  len = 3; }
        else if ((c & 0xF8)==0xF0) { cp = c & 0x07;  len = 4; }
        else { cp = 0xFFFD; len = 1; }
        if (i + len > n) { cp = 0xFFFD; len = 1; }
        else {
            for (int k = 1; k < len; ++k) {
                unsigned char cc = (unsigned char)s[i+k];
                if ((cc & 0xC0) != 0x80) { cp = 0xFFFD; len = 1; break; }
                cp = (cp << 6) | (cc & 0x3F);
            }
        }
        i += len;
        if (cp <= 0xFFFF) {
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
            buf.push_back((jchar)cp);
        } else {
            if (cp > 0x10FFFF) cp = 0xFFFD;
            cp -= 0x10000;
            buf.push_back((jchar)(0xD800 + (cp >> 10)));
            buf.push_back((jchar)(0xDC00 + (cp & 0x3FF)));
        }
    }
    jstring result = env->NewString(buf.data(), (jsize)buf.size());
    SAFE_EXCEPTION_CHECK(env);
    return result;
}

/* jstring -> 标准 UTF-8（UTF-16 代理对正确合成 4 字节；孤立代理项替换为 U+FFFD） */
static std::string jstring_to_utf8(JNIEnv* env, jstring js) {
    if (!js) return "";
    jsize len = env->GetStringLength(js);
    if (len <= 0) return "";
    const jchar* chars = env->GetStringChars(js, nullptr);
    if (!chars) { SAFE_EXCEPTION_CHECK(env); return ""; }
    std::string out;
    out.reserve((size_t)len * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < len && chars[i+1] >= 0xDC00 && chars[i+1] <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i+1] - 0xDC00);
                ++i;
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

/* ---------- 回调 ---------- */

static void on_log_cb(const char* msg, void* /*user*/) {
    if (!g_native_class || !g_on_log_mid) return;
    ScopedEnv se;
    if (!se.env) return;
    if (se.env->PushLocalFrame(8) == 0) {
        jstring jmsg = utf8_to_jstring(se.env, msg ? msg : "");
        if (jmsg) {
            se.env->CallStaticVoidMethod(g_native_class, g_on_log_mid, jmsg);
        }
        SAFE_EXCEPTION_CHECK(se.env);
        se.env->PopLocalFrame(nullptr);
    }
}

static void on_event_cb(const char* kind, const char* json, void* /*user*/) {
    if (!g_native_class || !g_on_event_mid) return;
    ScopedEnv se;
    if (!se.env) return;
    if (se.env->PushLocalFrame(8) == 0) {
        jstring jk = utf8_to_jstring(se.env, kind ? kind : "");
        jstring jj = utf8_to_jstring(se.env, json ? json : "");
        if (jk && jj) {
            se.env->CallStaticVoidMethod(g_native_class, g_on_event_mid, jk, jj);
        } else {
            /* 极少数 OOM 场景，丢日志但绝不崩溃 */
            LOGE("on_event_cb: jstring allocation failed, kind=%s", kind ? kind : "");
        }
        SAFE_EXCEPTION_CHECK(se.env);
        se.env->PopLocalFrame(nullptr);
    }
}

static int audio_read_cb(char* buf, int n, void* /*user*/) {
    if (!g_native_class || !g_on_audio_read_mid) return -1;
    if (n <= 0) return 0;
    ScopedEnv se;
    if (!se.env) return -1;
    if (se.env->PushLocalFrame(4) != 0) return -1;
    jbyteArray arr = se.env->NewByteArray(n);
    if (!arr) { SAFE_EXCEPTION_CHECK(se.env); se.env->PopLocalFrame(nullptr); return -1; }
    jint read = se.env->CallStaticIntMethod(g_native_class, g_on_audio_read_mid, arr);
    SAFE_EXCEPTION_CHECK(se.env);
    if (read > 0) {
        if (read > n) read = n;
        se.env->GetByteArrayRegion(arr, 0, read, reinterpret_cast<jbyte*>(buf));
        SAFE_EXCEPTION_CHECK(se.env);
    }
    se.env->PopLocalFrame(nullptr);
    return read;
}

static int audio_write_cb(char* buf, int n, void* /*user*/) {
    if (!g_native_class || !g_on_audio_write_mid) return 0;
    if (n <= 0) return 0;
    ScopedEnv se;
    if (!se.env) return 0;
    if (se.env->PushLocalFrame(4) != 0) return 0;
    jbyteArray arr = se.env->NewByteArray(n);
    if (!arr) { SAFE_EXCEPTION_CHECK(se.env); se.env->PopLocalFrame(nullptr); return 0; }
    se.env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(buf));
    SAFE_EXCEPTION_CHECK(se.env);
    jint written = se.env->CallStaticIntMethod(g_native_class, g_on_audio_write_mid, arr, n);
    SAFE_EXCEPTION_CHECK(se.env);
    se.env->PopLocalFrame(nullptr);
    return written;
}

/* ---------- JNI 生命周期 ---------- */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    if (g_lib_handle) {
        dlclose(g_lib_handle);
        g_lib_handle = nullptr;
    }
    if (g_native_class) {
        JNIEnv* env = nullptr;
        if (g_vm && g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
            env->DeleteGlobalRef(g_native_class);
        }
        g_native_class = nullptr;
    }
}

/* ---------- 导出函数 ---------- */

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_initLibrary(JNIEnv* env, jobject /* thiz */, jstring customPath) {
    std::string path = jstring_to_utf8(env, customPath);

    if (g_lib_handle) return 0;

    if (path.empty()) {
        g_lib_handle = dlopen("libmcilm_link.so", RTLD_NOW | RTLD_LOCAL);
    } else {
        g_lib_handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    }

    if (!g_lib_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return -1;
    }

    g_version        = (fn_version)        dlsym(g_lib_handle, "link_version");
    g_running        = (fn_running)        dlsym(g_lib_handle, "link_running");
    g_host_start     = (fn_host_start)     dlsym(g_lib_handle, "link_host_start");
    g_client_start   = (fn_client_start)   dlsym(g_lib_handle, "link_client_start");
    g_query          = (fn_query)          dlsym(g_lib_handle, "link_query");
    g_command        = (fn_command)        dlsym(g_lib_handle, "link_command");
    g_stop           = (fn_stop)           dlsym(g_lib_handle, "link_stop");
    g_free_string    = (fn_free_string)    dlsym(g_lib_handle, "link_free_string");
    g_set_log        = (fn_set_log)        dlsym(g_lib_handle, "link_set_log");
    g_set_event      = (fn_set_event)      dlsym(g_lib_handle, "link_set_event");
    g_set_discovery  = (fn_set_discovery)  dlsym(g_lib_handle, "link_set_discovery");
    g_set_extra      = (fn_set_extra)      dlsym(g_lib_handle, "link_set_extra");
    g_set_audio      = (fn_set_audio)      dlsym(g_lib_handle, "link_set_audio");

    if (!g_version || !g_host_start || !g_client_start || !g_query ||
        !g_command || !g_stop || !g_free_string || !g_set_log || !g_set_event || !g_set_discovery) {
        LOGE("dlsym missing required symbol");
        dlclose(g_lib_handle);
        g_lib_handle = nullptr;
        return -1;
    }

    if (!g_native_class) {
        jclass local_cls = env->FindClass("wasai/link/mcilm/top/link/MciLmNative");
        if (!local_cls) {
            SAFE_EXCEPTION_CHECK(env);
            dlclose(g_lib_handle);
            g_lib_handle = nullptr;
            return -1;
        }
        g_native_class = (jclass) env->NewGlobalRef(local_cls);
        env->DeleteLocalRef(local_cls);
        g_on_log_mid         = env->GetStaticMethodID(g_native_class, "onNativeLog", "(Ljava/lang/String;)V");
        g_on_event_mid       = env->GetStaticMethodID(g_native_class, "onNativeEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_on_audio_read_mid  = env->GetStaticMethodID(g_native_class, "onAudioRead", "([B)I");
        g_on_audio_write_mid = env->GetStaticMethodID(g_native_class, "onAudioWrite", "([BI)I");
        if (!g_on_log_mid || !g_on_event_mid) {
            SAFE_EXCEPTION_CHECK(env);
            env->DeleteGlobalRef(g_native_class);
            g_native_class = nullptr;
            dlclose(g_lib_handle);
            g_lib_handle = nullptr;
            return -1;
        }
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkVersion(JNIEnv* env, jobject /* thiz */) {
    if (!g_version) return utf8_to_jstring(env, "");
    char* s = g_version();
    jstring res = utf8_to_jstring(env, s ? s : "");
    if (s && g_free_string) g_free_string(s);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkRunning(JNIEnv*, jobject /* thiz */) {
    if (!g_running) return JNI_FALSE;
    return g_running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetLog(JNIEnv*, jobject /* thiz */, jboolean enable) {
    if (g_set_log) {
        if (enable) g_set_log(on_log_cb, nullptr);
        else g_set_log(nullptr, nullptr);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetEvent(JNIEnv*, jobject /* thiz */, jboolean enable) {
    if (g_set_event) {
        if (enable) g_set_event(on_event_cb, nullptr);
        else g_set_event(nullptr, nullptr);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetDiscovery(JNIEnv* env, jobject /* thiz */, jstring token, jint thresholdMs) {
    if (!g_set_discovery) return;
    std::string c_token = jstring_to_utf8(env, token);
    g_set_discovery(c_token.c_str(), thresholdMs);
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetAudio(JNIEnv*, jobject /* thiz */, jboolean enable) {
    if (!g_set_audio) return -1;
    if (enable) g_set_audio(audio_read_cb, audio_write_cb, nullptr);
    else g_set_audio(nullptr, nullptr, nullptr);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetExtra(JNIEnv* env, jobject /* thiz */, jstring extras) {
    if (!g_set_extra) return -1;
    std::string s = jstring_to_utf8(env, extras);
    return g_set_extra(s.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkHostStart(JNIEnv* env, jobject /* thiz */, jstring server, jstring room, jstring key, jstring pass, jstring features, jstring ports, jstring sni, jint plain) {
    if (!g_host_start) return -1;
    std::string s1 = jstring_to_utf8(env, server);
    std::string s2 = jstring_to_utf8(env, room);
    std::string s3 = jstring_to_utf8(env, key);
    std::string s4 = jstring_to_utf8(env, pass);
    std::string s5 = jstring_to_utf8(env, features);
    std::string s6 = jstring_to_utf8(env, ports);
    std::string s7 = jstring_to_utf8(env, sni);
    return g_host_start(s1.c_str(), s2.c_str(), s3.c_str(), s4.c_str(), s5.c_str(), s6.c_str(), s7.c_str(), plain);
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkClientStart(JNIEnv* env, jobject /* thiz */, jstring server, jstring room, jstring key, jstring pass, jstring ports, jstring sni, jboolean plain, jboolean forceRelay, jboolean preferPeers) {
    if (!g_client_start) return -1;
    std::string s1 = jstring_to_utf8(env, server);
    std::string s2 = jstring_to_utf8(env, room);
    std::string s3 = jstring_to_utf8(env, key);
    std::string s4 = jstring_to_utf8(env, pass);
    std::string s5 = jstring_to_utf8(env, ports);
    std::string s6 = jstring_to_utf8(env, sni);
    return g_client_start(s1.c_str(), s2.c_str(), s3.c_str(), s4.c_str(), s5.c_str(), s6.c_str(),
                          plain ? 1 : 0, forceRelay ? 1 : 0, preferPeers ? 1 : 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkQuery(JNIEnv* env, jobject /* thiz */, jstring what) {
    if (!g_query) return utf8_to_jstring(env, "");
    std::string s = jstring_to_utf8(env, what);
    char* res = g_query(s.c_str());
    jstring result = utf8_to_jstring(env, res ? res : "");
    if (res && g_free_string) g_free_string(res);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkCommand(JNIEnv* env, jobject /* thiz */, jstring line) {
    if (!g_command) return utf8_to_jstring(env, "");
    std::string s = jstring_to_utf8(env, line);
    char* res = g_command(s.c_str());
    jstring result = utf8_to_jstring(env, res ? res : "");
    if (res && g_free_string) g_free_string(res);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkStop(JNIEnv*, jobject /* thiz */) {
    if (g_stop) g_stop();
}
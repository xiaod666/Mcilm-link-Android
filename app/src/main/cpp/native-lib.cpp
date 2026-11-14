#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <cstring>

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
// [新增] link_set_extra
typedef int   (*fn_set_extra)(const char*);
// [新增] link_set_audio
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
static fn_set_extra g_set_extra = nullptr;   // [新增]
static fn_set_audio g_set_audio = nullptr;   // [新增]

static JavaVM* g_vm = nullptr;
static jclass g_native_class = nullptr;
static jmethodID g_on_log_mid = nullptr;
static jmethodID g_on_event_mid = nullptr;
static jmethodID g_on_audio_read_mid = nullptr;   // [新增]
static jmethodID g_on_audio_write_mid = nullptr;  // [新增]

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

static void on_log_cb(const char* msg, void* /*user*/) {
    if (!g_native_class || !g_on_log_mid) return;
    ScopedEnv se;
    if (!se.env) return;
    if (se.env->PushLocalFrame(8) == 0) {
        jstring jmsg = se.env->NewStringUTF(msg ? msg : "");
        if (jmsg) se.env->CallStaticVoidMethod(g_native_class, g_on_log_mid, jmsg);
        SAFE_EXCEPTION_CHECK(se.env);
        se.env->PopLocalFrame(nullptr);
    }
}

static void on_event_cb(const char* kind, const char* json, void* /*user*/) {
    if (!g_native_class || !g_on_event_mid) return;
    ScopedEnv se;
    if (!se.env) return;
    if (se.env->PushLocalFrame(8) == 0) {
        jstring jk = se.env->NewStringUTF(kind ? kind : "");
        jstring jj = se.env->NewStringUTF(json ? json : "");
        if (jk && jj) se.env->CallStaticVoidMethod(g_native_class, g_on_event_mid, jk, jj);
        SAFE_EXCEPTION_CHECK(se.env);
        se.env->PopLocalFrame(nullptr);
    }
}

// [新增] 采集回调：引擎从宿主拉 16k/mono/16bit LE PCM
static int audio_read_cb(char* buf, int n, void* /*user*/) {
    if (!g_native_class || !g_on_audio_read_mid) return -1;
    if (n <= 0) return 0;
    ScopedEnv se;
    if (!se.env) return -1;
    if (se.env->PushLocalFrame(4) != 0) return -1;
    jbyteArray arr = se.env->NewByteArray(n);
    if (!arr) { se.env->PopLocalFrame(nullptr); return -1; }
    jint read = se.env->CallStaticIntMethod(g_native_class, g_on_audio_read_mid, arr);
    SAFE_EXCEPTION_CHECK(se.env);
    if (read > 0) {
        if (read > n) read = n;
        se.env->GetByteArrayRegion(arr, 0, read, reinterpret_cast<jbyte*>(buf));
    }
    se.env->PopLocalFrame(nullptr);
    return read;
}

// [新增] 播放回调：引擎推 PCM 给宿主
static int audio_write_cb(char* buf, int n, void* /*user*/) {
    if (!g_native_class || !g_on_audio_write_mid) return 0;
    if (n <= 0) return 0;
    ScopedEnv se;
    if (!se.env) return 0;
    if (se.env->PushLocalFrame(4) != 0) return 0;
    jbyteArray arr = se.env->NewByteArray(n);
    if (!arr) { se.env->PopLocalFrame(nullptr); return 0; }
    se.env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(buf));
    jint written = se.env->CallStaticIntMethod(g_native_class, g_on_audio_write_mid, arr, n);
    SAFE_EXCEPTION_CHECK(se.env);
    se.env->PopLocalFrame(nullptr);
    return written;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    LOGD("JNI_OnLoad called");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    if (g_lib_handle) {
        LOGD("JNI_OnUnload: dlclose");
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

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_initLibrary(JNIEnv* env, jobject /* thiz */, jstring customPath) {
    const char* path = env->GetStringUTFChars(customPath, nullptr);

    if (g_lib_handle) {
        LOGD("Library already loaded, skipping.");
        env->ReleaseStringUTFChars(customPath, path);
        return 0;
    }

    if (strlen(path) == 0) {
        LOGD("Loading builtin: libmcilm_link.so");
        g_lib_handle = dlopen("libmcilm_link.so", RTLD_NOW | RTLD_LOCAL);
    } else {
        LOGD("Loading custom: %s", path);
        g_lib_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    }
    env->ReleaseStringUTFChars(customPath, path);

    if (!g_lib_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return -1;
    }

    g_version = (fn_version) dlsym(g_lib_handle, "link_version");
    g_running = (fn_running) dlsym(g_lib_handle, "link_running");
    g_host_start = (fn_host_start) dlsym(g_lib_handle, "link_host_start");
    g_client_start = (fn_client_start) dlsym(g_lib_handle, "link_client_start");
    g_query = (fn_query) dlsym(g_lib_handle, "link_query");
    g_command = (fn_command) dlsym(g_lib_handle, "link_command");
    g_stop = (fn_stop) dlsym(g_lib_handle, "link_stop");
    g_free_string = (fn_free_string) dlsym(g_lib_handle, "link_free_string");
    g_set_log = (fn_set_log) dlsym(g_lib_handle, "link_set_log");
    g_set_event = (fn_set_event) dlsym(g_lib_handle, "link_set_event");
    g_set_discovery = (fn_set_discovery) dlsym(g_lib_handle, "link_set_discovery");
    // [新增] 可选符号，旧库缺失时对应功能 no-op
    g_set_extra = (fn_set_extra) dlsym(g_lib_handle, "link_set_extra");
    g_set_audio = (fn_set_audio) dlsym(g_lib_handle, "link_set_audio");
    if (!g_set_extra) LOGE("link_set_extra symbol not found (old lib?)");
    if (!g_set_audio) LOGE("link_set_audio symbol not found (old lib?)");

    // 必选符号校验（不包含新加的可选符号，兼容旧库）
    if (!g_version || !g_host_start || !g_client_start || !g_query ||
        !g_command || !g_stop || !g_free_string || !g_set_log || !g_set_event || !g_set_discovery) {
        LOGE("dlsym failed: some symbols missing");
        dlclose(g_lib_handle);
        g_lib_handle = nullptr;
        return -1;
    }

    if (!g_native_class) {
        jclass local_cls = env->FindClass("wasai/link/mcilm/top/link/MciLmNative");
        if (!local_cls) {
            LOGE("FindClass MciLmNative failed");
            SAFE_EXCEPTION_CHECK(env);
            dlclose(g_lib_handle);
            g_lib_handle = nullptr;
            return -1;
        }
        g_native_class = (jclass) env->NewGlobalRef(local_cls);
        env->DeleteLocalRef(local_cls);
        g_on_log_mid = env->GetStaticMethodID(g_native_class, "onNativeLog", "(Ljava/lang/String;)V");
        g_on_event_mid = env->GetStaticMethodID(g_native_class, "onNativeEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_on_audio_read_mid = env->GetStaticMethodID(g_native_class, "onAudioRead", "([B)I");
        g_on_audio_write_mid = env->GetStaticMethodID(g_native_class, "onAudioWrite", "([BI)I");
        if (!g_on_log_mid || !g_on_event_mid) {
            LOGE("GetStaticMethodID failed (log/event)");
            SAFE_EXCEPTION_CHECK(env);
            env->DeleteGlobalRef(g_native_class);
            g_native_class = nullptr;
            dlclose(g_lib_handle);
            g_lib_handle = nullptr;
            return -1;
        }
        if (!g_on_audio_read_mid || !g_on_audio_write_mid) {
            LOGE("GetStaticMethodID failed (audio) — audio disabled");
            SAFE_EXCEPTION_CHECK(env);
        }
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkVersion(JNIEnv* env, jobject /* thiz */) {
    if (!g_version) return env->NewStringUTF("");
    char* s = g_version();
    jstring res = env->NewStringUTF(s ? s : "");
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
    const char* c_token = env->GetStringUTFChars(token, nullptr);
    g_set_discovery(c_token, thresholdMs);
    env->ReleaseStringUTFChars(token, c_token);
}

// [新增] 注册/清除音频注入。返回 0 成功，-1 未加载符号
extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetAudio(JNIEnv*, jobject /* thiz */, jboolean enable) {
    if (!g_set_audio) return -1;
    if (enable) g_set_audio(audio_read_cb, audio_write_cb, nullptr);
    else g_set_audio(nullptr, nullptr, nullptr);
    return 0;
}

// [新增] 附加参数（如 "minecraft=25565"）。空串 = 清除。
extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkSetExtra(JNIEnv* env, jobject /* thiz */, jstring extras) {
    if (!g_set_extra) return -1;
    const char* s = env->GetStringUTFChars(extras, nullptr);
    int rc = g_set_extra(s);
    env->ReleaseStringUTFChars(extras, s);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkHostStart(JNIEnv* env, jobject /* thiz */, jstring server, jstring room, jstring key, jstring pass, jstring features, jstring ports, jstring sni, jint plain) {
    if (!g_host_start) return -1;
    const char* s1 = env->GetStringUTFChars(server, nullptr);
    const char* s2 = env->GetStringUTFChars(room, nullptr);
    const char* s3 = env->GetStringUTFChars(key, nullptr);
    const char* s4 = env->GetStringUTFChars(pass, nullptr);
    const char* s5 = env->GetStringUTFChars(features, nullptr);
    const char* s6 = env->GetStringUTFChars(ports, nullptr);
    const char* s7 = env->GetStringUTFChars(sni, nullptr);

    int ret = g_host_start(s1, s2, s3, s4, s5, s6, s7, plain);

    env->ReleaseStringUTFChars(server, s1); env->ReleaseStringUTFChars(room, s2);
    env->ReleaseStringUTFChars(key, s3); env->ReleaseStringUTFChars(pass, s4);
    env->ReleaseStringUTFChars(features, s5); env->ReleaseStringUTFChars(ports, s6);
    env->ReleaseStringUTFChars(sni, s7);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkClientStart(JNIEnv* env, jobject /* thiz */, jstring server, jstring room, jstring key, jstring pass, jstring ports, jstring sni, jboolean plain, jboolean forceRelay, jboolean preferPeers) {
    if (!g_client_start) return -1;
    const char* s1 = env->GetStringUTFChars(server, nullptr);
    const char* s2 = env->GetStringUTFChars(room, nullptr);
    const char* s3 = env->GetStringUTFChars(key, nullptr);
    const char* s4 = env->GetStringUTFChars(pass, nullptr);
    const char* s5 = env->GetStringUTFChars(ports, nullptr);
    const char* s6 = env->GetStringUTFChars(sni, nullptr);

    int ret = g_client_start(s1, s2, s3, s4, s5, s6, plain ? 1 : 0, forceRelay ? 1 : 0, preferPeers ? 1 : 0);

    env->ReleaseStringUTFChars(server, s1); env->ReleaseStringUTFChars(room, s2);
    env->ReleaseStringUTFChars(key, s3); env->ReleaseStringUTFChars(pass, s4);
    env->ReleaseStringUTFChars(ports, s5); env->ReleaseStringUTFChars(sni, s6);
    return ret;
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkQuery(JNIEnv* env, jobject /* thiz */, jstring what) {
    if (!g_query) return env->NewStringUTF("");
    const char* s = env->GetStringUTFChars(what, nullptr);
    char* res = g_query(s);
    env->ReleaseStringUTFChars(what, s);

    jstring result = env->NewStringUTF(res ? res : "");
    if (res && g_free_string) g_free_string(res);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkCommand(JNIEnv* env, jobject /* thiz */, jstring line) {
    if (!g_command) return env->NewStringUTF("");
    const char* s = env->GetStringUTFChars(line, nullptr);
    char* res = g_command(s);
    env->ReleaseStringUTFChars(line, s);

    jstring result = env->NewStringUTF(res ? res : "");
    if (res && g_free_string) g_free_string(res);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_wasai_link_mcilm_top_link_MciLmNative_linkStop(JNIEnv*, jobject /* thiz */) {
    if (g_stop) g_stop();
}
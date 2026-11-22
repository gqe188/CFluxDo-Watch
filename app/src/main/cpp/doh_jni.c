#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stddef.h>
#include <stdint.h>

#define LOG_TAG "DohJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef int32_t (*fn_start_config_t)(const char*);
typedef void (*fn_void_t)(void);
typedef int32_t (*fn_i32_void_t)(void);
typedef int32_t (*fn_i32_str_t)(const char*);
typedef char* (*fn_str_void_t)(void);
typedef void (*fn_free_str_t)(char*);

static void* g_handle = NULL;
static fn_start_config_t g_start_config = NULL;
static fn_void_t g_stop = NULL;
static fn_i32_void_t g_is_running = NULL;
static fn_i32_void_t g_get_port = NULL;
static fn_void_t g_init_logging = NULL;
static fn_str_void_t g_select_status = NULL;
static fn_i32_void_t g_select_rerace = NULL;
static fn_i32_str_t g_network_changed = NULL;
static fn_str_void_t g_take_accel_update = NULL;
static fn_str_void_t g_get_embedded_ca = NULL;
static fn_i32_void_t g_clear_dns_cache = NULL;
static fn_free_str_t g_free_string = NULL;

static int ensure_loaded(void) {
    if (g_handle != NULL) return 1;
    g_handle = dlopen("libdoh_proxy.so", RTLD_NOW | RTLD_LOCAL);
    if (g_handle == NULL) {
        LOGE("dlopen libdoh_proxy.so failed: %s", dlerror());
        return 0;
    }

    g_start_config = (fn_start_config_t)dlsym(g_handle, "doh_proxy_start_with_config_json");
    g_stop = (fn_void_t)dlsym(g_handle, "doh_proxy_stop");
    g_is_running = (fn_i32_void_t)dlsym(g_handle, "doh_proxy_is_running");
    g_get_port = (fn_i32_void_t)dlsym(g_handle, "doh_proxy_get_port");
    g_init_logging = (fn_void_t)dlsym(g_handle, "doh_proxy_init_logging");
    g_select_status = (fn_str_void_t)dlsym(g_handle, "doh_proxy_select_status");
    g_select_rerace = (fn_i32_void_t)dlsym(g_handle, "doh_proxy_select_rerace");
    g_network_changed = (fn_i32_str_t)dlsym(g_handle, "doh_proxy_network_changed");
    g_take_accel_update = (fn_str_void_t)dlsym(g_handle, "doh_proxy_take_accel_update");
    g_get_embedded_ca = (fn_str_void_t)dlsym(g_handle, "doh_proxy_get_embedded_ca_pem");
    g_clear_dns_cache = (fn_i32_void_t)dlsym(g_handle, "doh_proxy_clear_dns_cache");
    g_free_string = (fn_free_str_t)dlsym(g_handle, "doh_proxy_free_string");

    if (!g_start_config || !g_stop || !g_free_string) {
        LOGE("Required symbols missing in libdoh_proxy.so");
        dlclose(g_handle);
        g_handle = NULL;
        return 0;
    }

    if (g_init_logging) {
        g_init_logging();
    }
    LOGI("Loaded libdoh_proxy.so successfully");
    return 1;
}

static jstring take_rust_string(JNIEnv* env, char* ptr) {
    if (ptr == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, ptr);
    if (g_free_string) {
        g_free_string(ptr);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeInit(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return ensure_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeStartWithConfigJson(
        JNIEnv* env, jclass clazz, jstring configJson) {
    (void)clazz;
    if (!ensure_loaded() || configJson == NULL) return -1;
    const char* c_json = (*env)->GetStringUTFChars(env, configJson, NULL);
    if (c_json == NULL) return -1;
    int32_t port = g_start_config(c_json);
    (*env)->ReleaseStringUTFChars(env, configJson, c_json);
    return (jint)port;
}

JNIEXPORT void JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeStop(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (g_stop) g_stop();
}

JNIEXPORT jboolean JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeIsRunning(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_is_running) return JNI_FALSE;
    return g_is_running() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeGetPort(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_get_port) return 0;
    return (jint)g_get_port();
}

JNIEXPORT jstring JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeSelectStatus(JNIEnv* env, jclass clazz) {
    (void)clazz;
    if (!g_select_status) return NULL;
    return take_rust_string(env, g_select_status());
}

JNIEXPORT jint JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeSelectRerace(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_select_rerace) return 0;
    return (jint)g_select_rerace();
}

JNIEXPORT jint JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeNetworkChanged(
        JNIEnv* env, jclass clazz, jstring sig) {
    (void)clazz;
    if (!g_network_changed || sig == NULL) return 0;
    const char* c_sig = (*env)->GetStringUTFChars(env, sig, NULL);
    if (c_sig == NULL) return -1;
    int32_t res = g_network_changed(c_sig);
    (*env)->ReleaseStringUTFChars(env, sig, c_sig);
    return (jint)res;
}

JNIEXPORT jstring JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeTakeAccelUpdate(JNIEnv* env, jclass clazz) {
    (void)clazz;
    if (!g_take_accel_update) return NULL;
    return take_rust_string(env, g_take_accel_update());
}

JNIEXPORT jstring JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeGetEmbeddedCaPem(JNIEnv* env, jclass clazz) {
    (void)clazz;
    if (!g_get_embedded_ca) return NULL;
    return take_rust_string(env, g_get_embedded_ca());
}

JNIEXPORT jboolean JNICALL
Java_com_cfluxdo_watch_net_DohProxyNative_nativeClearDnsCache(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_clear_dns_cache) return JNI_FALSE;
    return g_clear_dns_cache() != 0 ? JNI_TRUE : JNI_FALSE;
}

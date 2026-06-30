/*
 * hexrdp_jni.c — JNI bridge between Kotlin (com.gotohex.rdp.rdp.native.AFreeRdpBridge)
 * and the FreeRDP client library (libfreerdp / libfreerdp-client).
 *
 * Compatible: FreeRDP 3.x (tested 3.24.x)
 *
 * Changes vs original:
 *  - Added (void) casts for freerdp_settings_set_* [[nodiscard]] return values (FreeRDP 3.23+)
 *  - Added freerdp_context_new() return value check
 *  - Added PIXEL_FORMAT_BGRA32 pixel-format guard (FreeRDP 3.x uses pixel_format.h)
 *  - Proper NULL check before update callback assignment
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/channels/channels.h>
#include <freerdp/codec/color.h>
#include <winpr/synch.h>

#ifndef PIXEL_FORMAT_BGRA32
#define PIXEL_FORMAT_BGRA32 PIXEL_FORMAT_BGRA32_VER
#endif

#define TAG "hexrdp_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct
{
    rdpContext context;
    JavaVM* jvm;
    jobject bridgeObjGlobalRef;
    jmethodID onFrameMethod;
    jmethodID onStateMethod;
    jmethodID onErrorMethod;
} hexrdpContext;

#define HEXRDP_CTX(inst) ((hexrdpContext*)(inst)->context)

/* ── Callbacks invoked by FreeRDP's core on graphics updates ──────────── */

/* pEndPaint in the prebuilt FreeRDP3 headers is typedef'd as
 * BOOL (*pEndPaint)(rdpContext*) — no RECTANGLE_16 parameter.
 * The function must match that exact signature to satisfy the compiler.
 * We repaint the full frame on every EndPaint call; partial-region
 * optimisation is not possible without a region parameter. */
static BOOL hexrdp_on_frame(rdpContext* context)
{
    hexrdpContext* hctx = (hexrdpContext*)context;
    rdpGdi* gdi = context->gdi;
    if (!gdi || !gdi->primary_buffer)
        return TRUE;

    /* BUG-5 FIX: attach thread if FreeRDP called this callback from a thread
     * that was not registered with the JVM (common on ARMv7 / older FreeRDP).
     * Without AttachCurrentThread, GetEnv returns JNI_EDETACHED and every
     * frame is silently dropped → black screen with no visible error. */
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }

    /* No region parameter in this FreeRDP version: always repaint the full frame. */
    int x = 0;
    int y = 0;
    int w = (int)gdi->width;
    int h = (int)gdi->height;
    if (w <= 0 || h <= 0)
        return TRUE;

    /* CRIT-2 FIX: Guard against integer overflow before allocating the pixel buffer.
     * A malicious or misconfigured RDP server can advertise an enormous desktop
     * (e.g. 65535×65535 = ~4.3 billion pixels).  The expression `w * h` would
     * silently wrap to a small (or negative) value in 32-bit C arithmetic, causing
     * NewIntArray to allocate a tiny array while the memcpy loop writes far beyond
     * its end — undefined behaviour that manifests as a crash or memory corruption.
     *
     * Safe limit: 8K × 8K (64 M pixels × 4 bytes = 256 MB) is well above any
     * real-world remote desktop and well within Android's per-process memory budget.
     * Use int64_t for the comparison so the multiplication itself cannot overflow. */
#define HEXRDP_MAX_PIXELS (8192 * 8192)  /* 64 M pixels */
    if ((int64_t)w * h > HEXRDP_MAX_PIXELS) {
        LOGE("Desktop too large (%d×%d) — rejecting frame to prevent overflow", w, h);
        if (didAttach) (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
        return TRUE;
    }

    jintArray pixels = (*env)->NewIntArray(env, w * h);
    if (!pixels)
        return TRUE;

    jint* buf = (*env)->GetIntArrayElements(env, pixels, NULL);
    const UINT32 stride = gdi->stride;
    const BYTE* src = gdi->primary_buffer;
    for (int row = 0; row < h; row++)
    {
        const UINT32* srcRow = (const UINT32*)(src + (size_t)row * stride) + x;
        memcpy(buf + (size_t)row * w, srcRow, (size_t)w * sizeof(jint));
    }
    (*env)->ReleaseIntArrayElements(env, pixels, buf, 0);

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onFrameMethod,
                            x, y, w, h, pixels,
                            (jboolean)JNI_TRUE);  /* full-frame repaint */
    (*env)->DeleteLocalRef(env, pixels);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
    return TRUE;
}

/* ── FreeRDP lifecycle callbacks ────────────────────────────────────────── */

static BOOL hexrdp_pre_connect(freerdp* instance)
{
    rdpSettings* settings = instance->context->settings;
    (void)freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    return TRUE;
}

static BOOL hexrdp_post_connect(freerdp* instance)
{
    if (!gdi_init(instance, PIXEL_FORMAT_BGRA32))
        return FALSE;

    rdpUpdate* update = instance->context->update;
    if (update)
        update->EndPaint = hexrdp_on_frame;  // BUG-1 FIX: was NULL → black screen on all RDP sessions

    /* BUG-STATE FIX: onNativeState was stored in nativeInit but never called,
     * so bridge.stateChanges never emitted any value from native, leaving
     * RdpRemoteAdapter._sessionState stuck at CONNECTING forever and the UI
     * showing a loading screen even when the connection succeeded.
     * Notify Kotlin that we are now CONNECTED (state = 2). */
    hexrdpContext* hctx = (hexrdpContext*)instance->context;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;   /* connection is alive; state notification failure is non-fatal */
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onStateMethod, 2 /* CONNECTED */);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);

    return TRUE;
}

static void hexrdp_post_disconnect(freerdp* instance)
{
    gdi_free(instance);

    /* BUG-STATE FIX: mirror of the post_connect fix — notify Kotlin that the
     * session is now DISCONNECTED (state = 0) so the UI can react correctly. */
    hexrdpContext* hctx = (hexrdpContext*)instance->context;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onStateMethod, 0 /* DISCONNECTED */);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* ── JNI exported functions ─────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeInit(JNIEnv* env, jobject thiz)
{
    freerdp* instance = freerdp_new();
    if (!instance)
        return 0;

    instance->ContextSize = sizeof(hexrdpContext);
    instance->ContextNew  = NULL;
    instance->ContextFree = NULL;

    /* freerdp_context_new returns BOOL in FreeRDP 3.x */
    if (!freerdp_context_new(instance))
    {
        freerdp_free(instance);
        return 0;
    }

    hexrdpContext* hctx = HEXRDP_CTX(instance);
    (*env)->GetJavaVM(env, &hctx->jvm);
    hctx->bridgeObjGlobalRef = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    hctx->onFrameMethod = (*env)->GetMethodID(env, cls, "onNativeFrame", "(IIII[IZ)V");
    hctx->onStateMethod = (*env)->GetMethodID(env, cls, "onNativeState", "(I)V");
    hctx->onErrorMethod = (*env)->GetMethodID(env, cls, "onNativeError", "(Ljava/lang/String;)V");

    instance->PreConnect    = hexrdp_pre_connect;
    instance->PostConnect   = hexrdp_post_connect;
    instance->PostDisconnect = hexrdp_post_disconnect;

    return (jlong)(intptr_t)instance;
}

JNIEXPORT jboolean JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeConnect(
    JNIEnv* env, jobject thiz, jlong handle,
    jstring jHost, jint jPort, jstring jUsername, jstring jPassword, jstring jDomain,
    jint jWidth, jint jHeight, jboolean jUseNla,
    jboolean jGatewayEnabled, jstring jGwHost, jint jGwPort, jstring jGwUser, jstring jGwPass, jstring jGwDomain,
    jint jColorDepth, jint jCompressionQuality, jint jPerformanceMode, jboolean jIgnoreCert)
/* BUG-1 FIX: added jColorDepth, jCompressionQuality, jPerformanceMode (were declared in
 * Kotlin external fun but missing here → UnsatisfiedLinkError / stack corruption on connect).
 * BUG-4 FIX: added jIgnoreCert so TLS cert validation is not permanently disabled. */
{
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return JNI_FALSE;
    rdpSettings* settings = instance->context->settings;

    const char* host   = (*env)->GetStringUTFChars(env, jHost,     NULL);
    const char* user   = (*env)->GetStringUTFChars(env, jUsername, NULL);
    const char* pass   = (*env)->GetStringUTFChars(env, jPassword, NULL);
    const char* domain = (*env)->GetStringUTFChars(env, jDomain,   NULL);
    /* BUG-6 FIX: GetStringUTFChars returns NULL on OOM (low-RAM devices with ~512MB).
     * Passing NULL to freerdp_settings_set_string causes a native crash. */
    if (!host || !user || !pass || !domain) {
        if (host)   (*env)->ReleaseStringUTFChars(env, jHost,     host);
        if (user)   (*env)->ReleaseStringUTFChars(env, jUsername, user);
        if (pass)   (*env)->ReleaseStringUTFChars(env, jPassword, pass);
        if (domain) (*env)->ReleaseStringUTFChars(env, jDomain,   domain);
        return JNI_FALSE;
    }

    /* (void) casts suppress [[nodiscard]] warnings in FreeRDP 3.23+ */
    (void)freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ServerPort,     (UINT32)jPort);
    (void)freerdp_settings_set_string(settings, FreeRDP_Username,       user);
    (void)freerdp_settings_set_string(settings, FreeRDP_Password,       pass);
    (void)freerdp_settings_set_string(settings, FreeRDP_Domain,         domain);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth,   (UINT32)jWidth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight,  (UINT32)jHeight);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_NlaSecurity,    jUseNla ? TRUE : FALSE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_TlsSecurity,    TRUE);
    // CRIT-1 FIX: RdpSecurity=TRUE allows the server to negotiate a downgrade
    // to Classic RDP (RC4, no server authentication) even when NLA/TLS are
    // requested.  A malicious server or MITM can exploit this to capture
    // credentials.  Always disable it so only TLS/NLA are accepted.
    (void)freerdp_settings_set_bool  (settings, FreeRDP_RdpSecurity,    FALSE);
    /* BUG-4 FIX: was always TRUE → every session vulnerable to MITM.
     * Now uses caller-supplied jIgnoreCert (default false from Kotlin). */
    (void)freerdp_settings_set_bool  (settings, FreeRDP_IgnoreCertificate, jIgnoreCert ? TRUE : FALSE);
    /* BUG-1 FIX: apply the three parameters that were declared in Kotlin but ignored in C. */
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth,        (UINT32)jColorDepth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_PerformanceFlags,  (UINT32)jPerformanceMode);
    /* BUG-QUALITY FIX: jCompressionQuality is an app-level value in the range 0–100
     * (higher = better quality). FreeRDP_RemoteFxCodecMode expects a 0–2 enum:
     *   0 = VIDEO (high-motion, lower quality)
     *   1 = IMAGE (low-motion, higher quality)
     * Map the 0-100 range: ≥50 → IMAGE (1, higher quality), <50 → VIDEO (0, lower quality).
     * Previously passing a raw value like 75 was way outside the valid enum range and
     * caused undefined behaviour in the FreeRDP codec. */
    {
        UINT32 rfxMode = (jCompressionQuality >= 50) ? 1 /* IMAGE */ : 0 /* VIDEO */;
        (void)freerdp_settings_set_uint32(settings, FreeRDP_RemoteFxCodecMode, rfxMode);
    }

    /* NEW-CRIT-3 FIX: Zero pass immediately after handing it to FreeRDP and before
     * freerdp_connect() — which can block for 5-30 seconds (TCP + TLS + NLA handshake).
     * During that window, a native heap snapshot would expose the cleartext password.
     * explicit_bzero() is used instead of memset() because the compiler is permitted to
     * eliminate a plain memset() as a Dead Store if it determines the buffer is not read
     * afterward.  explicit_bzero() is guaranteed to execute on Android NDK (POSIX.1-2017).
     * FreeRDP has already copied the string into its own internal settings buffer via
     * freerdp_settings_set_string(), so zeroing our copy is safe here.
     * domain contains no secret but is zeroed for uniformity (minimises attack surface). */
    explicit_bzero((void*)pass,   strlen(pass));
    (*env)->ReleaseStringUTFChars(env, jPassword, pass);
    pass = NULL;
    explicit_bzero((void*)domain, strlen(domain));
    (*env)->ReleaseStringUTFChars(env, jDomain,   domain);
    domain = NULL;

    if (jGatewayEnabled)
    {
        const char* gwHost   = (*env)->GetStringUTFChars(env, jGwHost,   NULL);
        const char* gwUser   = (*env)->GetStringUTFChars(env, jGwUser,   NULL);
        const char* gwPass   = (*env)->GetStringUTFChars(env, jGwPass,   NULL);
        const char* gwDomain = (*env)->GetStringUTFChars(env, jGwDomain, NULL);

        /* BUG-OOM FIX: GetStringUTFChars returns NULL on OOM (low-RAM devices).
         * Passing NULL to freerdp_settings_set_string causes a native crash.
         * Release whatever was allocated and bail out cleanly. */
        if (!gwHost || !gwUser || !gwPass || !gwDomain)
        {
            if (gwHost)   (*env)->ReleaseStringUTFChars(env, jGwHost,   gwHost);
            if (gwUser)   (*env)->ReleaseStringUTFChars(env, jGwUser,   gwUser);
            if (gwPass)   { explicit_bzero((void*)gwPass, strlen(gwPass)); (*env)->ReleaseStringUTFChars(env, jGwPass,   gwPass); }
            if (gwDomain) (*env)->ReleaseStringUTFChars(env, jGwDomain, gwDomain);
            (*env)->ReleaseStringUTFChars(env, jHost,     host);
            (*env)->ReleaseStringUTFChars(env, jUsername, user);
            /* pass and domain already released above */
            return JNI_FALSE;
        }

        (void)freerdp_settings_set_bool  (settings, FreeRDP_GatewayEnabled,      TRUE);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayHostname,     gwHost);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort,         (UINT32)jGwPort);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayUsername,     gwUser);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayPassword,     gwPass);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayDomain,       gwDomain);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayUsageMethod,  1 /* TSC_PROXY_MODE_DIRECT */);

        /* NEW-CRIT-3 FIX: Zero gwPass before freerdp_connect() for the same reason as pass. */
        (*env)->ReleaseStringUTFChars(env, jGwHost,   gwHost);
        (*env)->ReleaseStringUTFChars(env, jGwUser,   gwUser);
        explicit_bzero((void*)gwPass,   strlen(gwPass));
        (*env)->ReleaseStringUTFChars(env, jGwPass,   gwPass);
        (*env)->ReleaseStringUTFChars(env, jGwDomain, gwDomain);
    }

    (*env)->ReleaseStringUTFChars(env, jHost,     host);
    (*env)->ReleaseStringUTFChars(env, jUsername, user);
    /* pass and domain already zeroed and released before this block */

    BOOL ok = freerdp_connect(instance);
    if (!ok)
    {
        hexrdpContext* hctx = HEXRDP_CTX(instance);
        UINT32 code = freerdp_get_last_error(instance->context);
        const char* name = freerdp_get_last_error_name(code);
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onErrorMethod,
                                (*env)->NewStringUTF(env, name ? name : "Unknown FreeRDP error"));

        /* CRIT-NEW-1 FIX: Emit AUTH_FAILED (state=3) for logon/credential errors so
         * Kotlin can stop auto-reconnect immediately instead of retrying 3 times with
         * the same wrong password.  FreeRDP auth-failure codes:
         *   ERRCONNECT_LOGON_FAILURE          0x00020014
         *   ERRCONNECT_WRONG_PASSWORD         0x00020009  (some server variants)
         *   ERRCONNECT_ACCOUNT_LOCKED_OUT     0x00020004
         *   ERRCONNECT_ACCOUNT_DISABLED       0x00020003
         *   ERRCONNECT_PASSWORD_CERTAINLY_EXPIRED 0x0002000D
         * All share the high nibble 0x0002xxxx ("connect error" category).
         * We check the specific values that are unambiguously credential errors. */
        BOOL isAuthFailure =
            code == 0x00020014 /* ERRCONNECT_LOGON_FAILURE          */ ||
            code == 0x00020009 /* ERRCONNECT_WRONG_PASSWORD         */ ||
            code == 0x00020004 /* ERRCONNECT_ACCOUNT_LOCKED_OUT     */ ||
            code == 0x00020003 /* ERRCONNECT_ACCOUNT_DISABLED       */ ||
            code == 0x0002000D /* ERRCONNECT_PASSWORD_CERTAINLY_EXPIRED */;

        JNIEnv* stateEnv = env;  /* same thread; env is valid */
        if (isAuthFailure)
        {
            /* state 3 = AUTH_FAILED (mapped in AFreeRdpBridge.onNativeState / RdpRemoteAdapter) */
            (*stateEnv)->CallVoidMethod(stateEnv, hctx->bridgeObjGlobalRef,
                                         hctx->onStateMethod, 3 /* AUTH_FAILED */);
        }
    }

    /* CRIT-NEW-2 FIX: nativeConnect is declared jboolean but had no return statement
     * on the success path — undefined behaviour in C.  On ARM64 the return register
     * (x0) is left holding whatever the last CallVoidMethod call placed there (usually
     * 0 / false), so Kotlin always received false even when freerdp_connect() returned
     * TRUE.  RdpRemoteAdapter.connect() then cancelled adapterScope and freed the live
     * native connection, making every RDP session appear to fail immediately after
     * the native side successfully connected. */
    return (jboolean)ok;
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeSendMouse(
    JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint flags)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    (void)freerdp_input_send_mouse_event(instance->context->input,
                                          (UINT16)flags, (UINT16)x, (UINT16)y);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeSendKey(
    JNIEnv* env, jobject thiz, jlong handle, jint scanCode, jboolean down, jboolean extended)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    UINT16 kflags = (UINT16)((down ? 0 : KBD_FLAGS_RELEASE) | (extended ? KBD_FLAGS_EXTENDED : 0));
    (void)freerdp_input_send_keyboard_event(instance->context->input, kflags, (UINT16)scanCode);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    freerdp_disconnect(instance);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeFree(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    hexrdpContext* hctx = HEXRDP_CTX(instance);
    if (hctx && hctx->bridgeObjGlobalRef)
        (*env)->DeleteGlobalRef(env, hctx->bridgeObjGlobalRef);
    freerdp_context_free(instance);
    freerdp_free(instance);
}

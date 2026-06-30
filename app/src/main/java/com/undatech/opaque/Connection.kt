package com.undatech.opaque

/**
 * STUB — يحاكي com.undatech.opaque.Connection من مكتبة bVNC/remoteClientLib.
 *
 * المكتبة الأصلية تتطلب:
 *   - NDK + FreeRDP (مكتبات C++ مُجمَّعة)
 *   - sqlcipher prebuilt AAR
 *   - عدة sub-projects محلية (:remoteClientLib, :pubkeyGenerator, …)
 *
 * هذه الـ stubs تُبقي الكود يُترجَم ويمر lint بدون إضافة dependency خارجية
 * لا يمكن لـ JitPack بناؤها. عند الحاجة لميزة VNC الفعلية، يجب دمج
 * iiordanov/remote-desktop-clients كـ Git submodule أو تضمين AARs المُجمَّعة مسبقاً.
 */
class Connection {
    var address: String = ""
    var port: Int = 5900
    var password: String = ""
    /** نوع الإدخال — يتطابق مع ثوابت RemotePointer.INPUT_MODE_* */
    var inputMode: String = ""
    var userName: String = ""
    var rdpDomain: String = ""
}


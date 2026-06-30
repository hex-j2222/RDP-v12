package com.gotohex.rdp

import android.app.Application
import android.content.ComponentCallbacks2
import com.gotohex.rdp.data.repository.ConnectionLogRepository
import com.gotohex.rdp.data.repository.RdpProfileRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HexRDPApp : Application() {

    @Inject lateinit var connectionLogRepository: ConnectionLogRepository
    // FIX B3: نحتاج إلى RdpProfileRepository لإعادة تهيئة حالة الاتصال عند بدء التطبيق
    @Inject lateinit var profileRepository: RdpProfileRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // FIX B3: إعادة تعيين isConnected=false لجميع البطاقات عند بدء التطبيق.
            // بدون هذا تظل البطاقات مُعلَّمة كـ "متصل" بعد أي كراش أو إغلاق مفاجئ.
            profileRepository.resetAllConnectionStates()
            // تنظيف سجلات الاتصال المعلقة وحذف الإدخالات القديمة (أكثر من 30 يوماً)
            connectionLogRepository.closeOrphanedLogs()
            connectionLogRepository.purgeOld()
        }
    }

    // BUG-L FIX: Implement onTrimMemory so the OS can reclaim the large-heap
    // double-buffer bitmaps (up to 15 MB per session) on low-memory devices
    // (2-3 GB RAM). Without this, the OOM Killer terminates the app silently.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Notify active sessions to release non-visible frame buffers.
            // The bitmaps will be re-allocated on the next frame update.
            TrimMemoryBus.notifyTrim(level)
        }
    }
}
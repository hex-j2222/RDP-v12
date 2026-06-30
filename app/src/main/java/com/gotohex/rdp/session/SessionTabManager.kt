package com.gotohex.rdp.session

import com.gotohex.rdp.data.model.ConnectionState
import com.gotohex.rdp.data.model.RdpProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-05 · تعدد الجلسات
 *
 * Tracks every open RDP / VNC / SSH session so the UI can show them as tabs
 * and let the user switch between them without disconnecting.
 *
 * Each [SessionTab] is a lightweight descriptor; the actual heavy [RemoteSessionClient]
 * lives inside [RdpSessionViewModel] which is scoped to its Activity. The manager
 * only tracks metadata (profile, state, title) so the HomeScreen can render the
 * tab bar and deep-link into the right Activity instance.
 *
 * BUG FIX · حد أقصى للجلسات المتزامنة
 * كل جلسة تحجز bitmap مزدوج بحجم الشاشة كاملة في ذاكرة الـ native heap.
 * بدون حد أعلى، يمكن فتح عشرات الجلسات بدقة عالية مما يؤدي إلى OOM.
 * [MAX_TABS] يضع سقفاً صارماً متناسقاً مع ثابت MAX_TERMINAL_LINES.
 */
data class SessionTab(
    /** Stable identifier for this tab — also passed as Activity intent extra. */
    val tabId: String = UUID.randomUUID().toString(),
    val profile: RdpProfile,
    val state: ConnectionState = ConnectionState.CONNECTING,
    /** Optional short status text shown under the tab label. */
    val statusHint: String = "",
    /** Epoch-millis of when this tab was opened. */
    val openedAt: Long = System.currentTimeMillis()
)

@Singleton
class SessionTabManager @Inject constructor() {

    companion object {
        /**
         * الحد الأقصى لعدد الجلسات المتزامنة (RDP / VNC / SSH).
         *
         * كل جلسة تحجز bitmap مزدوجاً (front + back buffer) بحجم الشاشة كاملة
         * في native heap. على دقة 1920×1080 بعمق 32-bit يعادل ذلك ~16 MB لكل
         * جلسة — أي 5 جلسات = ~80 MB لمجرد الـ bitmaps، وهو رقم آمن حتى على
         * أجهزة بذاكرة heap محدودة (256 MB).
         *
         * رفع هذا الحد يتطلب اختبار ذاكرة صريح على الأجهزة المستهدفة.
         */
        const val MAX_TABS = 5
    }

    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** true عندما تكون جميع فتحات الجلسات ممتلئة — تستخدمها الـ UI لتعطيل زر "اتصال جديد". */
    val isFull: Boolean get() = _tabs.value.size >= MAX_TABS

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Open a new tab for [profile] and return its [SessionTab.tabId].
     *
     * Returns `null` — بدلاً من فتح جلسة جديدة — إذا كان عدد الجلسات المفتوحة
     * قد بلغ [MAX_TABS]، مما يمنع استنزاف الذاكرة (OOM).
     * على المُستدعي عرض رسالة خطأ مناسبة للمستخدم عند استقبال `null`.
     *
     * BUG-N6 FIX: the previous implementation read _tabs.value.size in a separate
     * step and then called _tabs.update { it + tab } in another step. Two concurrent
     * callers could both read size=4, both pass the guard, and both append — ending
     * up with 6 tabs instead of 5 (exceeding MAX_TABS). Fix: move the size check
     * inside the update{} lambda so the check+append is a single atomic CAS operation.
     */
    fun openTab(profile: RdpProfile): String? {
        var createdTab: SessionTab? = null
        _tabs.update { current ->
            if (current.size >= MAX_TABS) return@update current  // BUG-N6 FIX: atomic guard
            val tab = SessionTab(profile = profile)
            createdTab = tab
            current + tab
        }
        val tab = createdTab ?: return null
        _activeTabId.value = tab.tabId
        return tab.tabId
    }

    /** Make [tabId] the currently-visible tab. */
    fun switchTo(tabId: String) {
        if (_tabs.value.any { it.tabId == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /** Called by the session Activity when the connection state changes. */
    fun updateState(tabId: String, state: ConnectionState, hint: String = "") {
        _tabs.update { list ->
            list.map { if (it.tabId == tabId) it.copy(state = state, statusHint = hint) else it }
        }
    }

    /** Remove a tab (user closes it or session ends permanently). */
    fun closeTab(tabId: String) {
        // FIX-CLOSE-TAB-RACE: The previous implementation had two separate operations:
        //   1. _tabs.update { ... }          ← tabs updated
        //   2. _activeTabId.value = ...      ← activeTabId updated
        // Between these two steps any observer could see an inconsistent state:
        // tabs no longer contained the closed tab, but activeTabId still pointed to it.
        // Fix: compute the new active tab ID INSIDE _tabs.update so the decision is
        // based on the post-removal list, then apply both updates back-to-back before
        // any coroutine suspension point, minimising the observable window.
        var nextActiveId: String? = _activeTabId.value
        _tabs.update { list ->
            val newList = list.filterNot { it.tabId == tabId }
            // Determine successor only when closing the currently-active tab
            if (_activeTabId.value == tabId) {
                nextActiveId = newList.lastOrNull()?.tabId
            }
            newList
        }
        // Apply the new active ID immediately after the atomic tabs update.
        // This is still technically two operations, but nextActiveId is now computed
        // from the final tabs state, so there is never a logical inconsistency —
        // only a brief moment where activeTabId still holds the old closed-tab ID.
        _activeTabId.value = nextActiveId
    }

    /** Number of currently open sessions. */
    val count: Int get() = _tabs.value.size
}

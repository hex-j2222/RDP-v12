package com.undatech.opaque.input

/**
 * STUB — يحاكي com.undatech.opaque.input.RemotePointer من مكتبة bVNC.
 *
 * يُوفِّر ثوابت نوع الإدخال المستخدمة في [com.undatech.opaque.Connection.inputMode]:
 *   - [INPUT_MODE_TOUCH_TOUCHPAD]  → وضع لوحة التتبع (سحب = تحريك، نقر = ضغط)
 *   - [INPUT_MODE_TOUCH_DIRECT]    → وضع اللمس المباشر (اللمس = مؤشر)
 */
object RemotePointer {
    const val INPUT_MODE_TOUCH_TOUCHPAD = "touchpad"
    const val INPUT_MODE_TOUCH_DIRECT   = "direct"
}


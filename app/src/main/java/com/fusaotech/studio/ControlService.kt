package com.fusaotech.studio

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * FusãoTechX Mz • Controlo do telefone.
 * Um Serviço de Acessibilidade é a forma oficial de uma app (sem root) tocar,
 * deslizar e escrever no ecrã. É por isto que a app pede a Acessibilidade.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var inst: ControlService? = null
    }

    override fun onServiceConnected() { inst = this }
    override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun tap(x: Int, y: Int) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo((x + 1).toFloat(), (y + 1).toFloat()) }
        gesto(p, 0, 55)
    }

    fun longPress(x: Int, y: Int) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        gesto(p, 0, 650)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        gesto(p, 0, if (ms < 20) 160 else ms)
    }

    private fun gesto(p: Path, start: Long, dur: Long) {
        try {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, start, dur.coerceAtLeast(1)))
                .build()
            dispatchGesture(g, null, null)
        } catch (_: Exception) {}
    }

    fun global(action: String): Boolean {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "split" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return false
        }
        return try { performGlobalAction(a) } catch (_: Exception) { false }
    }

    /** Escreve texto no campo que estiver focado. */
    fun type(text: String): Boolean {
        val node = focado() ?: return false
        return try {
            val atual = node.text?.toString() ?: ""
            val novo = atual + text
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, novo)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
    }

    fun backspace(): Boolean {
        val node = focado() ?: return false
        return try {
            val atual = node.text?.toString() ?: ""
            if (atual.isEmpty()) return true
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, atual.substring(0, atual.length - 1))
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
    }

    fun limparCampo(): Boolean {
        val node = focado() ?: return false
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
    }

    private fun focado(): AccessibilityNodeInfo? {
        return try {
            val r = rootInActiveWindow ?: return null
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        } catch (_: Exception) { null }
    }
}

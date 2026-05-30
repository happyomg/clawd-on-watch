package com.clawd.watch.domain

object StateChipConfig {

    fun chipColor(state: ClawdState): Int = when (state) {
        ClawdState.THINKING -> 0xFF8B8BF5.toInt()
        ClawdState.WORKING -> 0xFF4ADE80.toInt()
        ClawdState.JUGGLING -> 0xFFFBBF24.toInt()
        ClawdState.ERROR -> 0xFFF87171.toInt()
        ClawdState.NOTIFICATION -> 0xFFFBBF24.toInt()
        ClawdState.SWEEPING -> 0xFF9CA3AF.toInt()
        ClawdState.ATTENTION -> 0xFF4ADE80.toInt()
        ClawdState.CARRYING -> 0xFF60A5FA.toInt()
        ClawdState.IDLE -> 0xFF6B7280.toInt()
        ClawdState.SLEEPING -> 0xFF4B5563.toInt()
        ClawdState.YAWNING,
        ClawdState.DOZING,
        ClawdState.COLLAPSING -> 0xFF4B5563.toInt()
        ClawdState.WAKING -> 0xFF6B7280.toInt()
    }

    fun chipLabel(state: ClawdState): String = when (state) {
        ClawdState.THINKING -> "thinking"
        ClawdState.WORKING -> "working"
        ClawdState.JUGGLING -> "juggling"
        ClawdState.ERROR -> "error"
        ClawdState.NOTIFICATION -> "alert"
        ClawdState.SWEEPING -> "sweeping"
        ClawdState.ATTENTION -> "done"
        ClawdState.CARRYING -> "carrying"
        ClawdState.IDLE -> "idle"
        ClawdState.SLEEPING -> "sleeping"
        ClawdState.YAWNING -> "yawning"
        ClawdState.DOZING -> "dozing"
        ClawdState.COLLAPSING -> "dozing"
        ClawdState.WAKING -> "waking"
    }

    fun isChipVisible(state: ClawdState): Boolean = when (state) {
        ClawdState.IDLE, ClawdState.SLEEPING,
        ClawdState.YAWNING, ClawdState.DOZING,
        ClawdState.COLLAPSING, ClawdState.WAKING -> false
        else -> true
    }

    fun statusDotColor(state: ClawdState): Int = when {
        state == ClawdState.ERROR -> 0xFFF87171.toInt()
        state == ClawdState.SLEEPING || state.isSleepSequence -> 0xFF4B5563.toInt()
        state == ClawdState.IDLE -> 0xFF6B7280.toInt()
        else -> 0xFF4ADE80.toInt()
    }

    fun connectionIndicator(connected: Boolean, activeCount: Int): String = when {
        !connected -> "○"
        activeCount <= 0 -> "⬤"
        else -> "⬤ $activeCount"
    }

    const val COLOR_CONNECTED = 0xFF4ADE80.toInt()
    const val COLOR_DISCONNECTED = 0xFFF87171.toInt()
    const val COLOR_INDICATOR_TEXT = 0xCCFFFFFF.toInt()
    const val BG_INDICATOR = 0x33FFFFFF
    const val BG_CHIP = 0x66000000
}

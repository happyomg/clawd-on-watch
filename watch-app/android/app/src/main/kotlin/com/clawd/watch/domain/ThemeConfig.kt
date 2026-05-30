package com.clawd.watch.domain

object ThemeConfig {

    private val STATE_SVG = mapOf(
        ClawdState.IDLE to "clawd-idle-follow.svg",
        ClawdState.YAWNING to "clawd-idle-yawn.svg",
        ClawdState.DOZING to "clawd-idle-doze.svg",
        ClawdState.COLLAPSING to "clawd-collapse-sleep.svg",
        ClawdState.THINKING to "clawd-working-thinking.svg",
        ClawdState.WORKING to "clawd-working-typing.svg",
        ClawdState.JUGGLING to "clawd-headphones-groove.svg",
        ClawdState.SWEEPING to "clawd-working-sweeping.svg",
        ClawdState.ERROR to "clawd-error.svg",
        ClawdState.ATTENTION to "clawd-happy.svg",
        ClawdState.NOTIFICATION to "clawd-notification.svg",
        ClawdState.CARRYING to "clawd-working-carrying.svg",
        ClawdState.SLEEPING to "clawd-sleeping.svg",
        ClawdState.WAKING to "clawd-wake.svg"
    )

    data class TierEntry(val minSessions: Int, val file: String)

    private val WORKING_TIERS = listOf(
        TierEntry(3, "clawd-working-building.svg"),
        TierEntry(2, "clawd-headphones-groove.svg"),
        TierEntry(1, "clawd-working-typing.svg")
    )

    private val JUGGLING_TIERS = listOf(
        TierEntry(2, "clawd-working-juggling.svg"),
        TierEntry(1, "clawd-headphones-groove.svg")
    )

    data class IdleAnimation(val file: String, val durationMs: Long)

    val IDLE_ANIMATIONS = listOf(
        IdleAnimation("clawd-idle-look.svg", 6500),
        IdleAnimation("clawd-working-debugger.svg", 14000),
        IdleAnimation("clawd-idle-reading.svg", 14000)
    )

    private val DISPLAY_HINT_MAP = mapOf(
        "clawd-working-building.svg" to "clawd-working-building.svg",
        "clawd-working-typing.svg" to "clawd-working-typing.svg",
        "clawd-headphones-groove.svg" to "clawd-headphones-groove.svg",
        "clawd-working-juggling.svg" to "clawd-working-juggling.svg",
        "clawd-working-conducting.svg" to "clawd-working-juggling.svg",
        "clawd-idle-reading.svg" to "clawd-idle-reading.svg",
        "clawd-working-debugger.svg" to "clawd-working-debugger.svg",
        "clawd-working-thinking.svg" to "clawd-working-thinking.svg"
    )

    private val MIN_DISPLAY_MS = mapOf(
        ClawdState.ATTENTION to 4000L,
        ClawdState.ERROR to 5000L,
        ClawdState.SWEEPING to 5500L,
        ClawdState.NOTIFICATION to 5000L,
        ClawdState.CARRYING to 3000L,
        ClawdState.WORKING to 1000L,
        ClawdState.THINKING to 1000L
    )

    private val AUTO_RETURN_MS = mapOf(
        ClawdState.ATTENTION to 4000L,
        ClawdState.ERROR to 5000L,
        ClawdState.SWEEPING to 300000L,
        ClawdState.NOTIFICATION to 5000L,
        ClawdState.CARRYING to 3000L
    )

    const val YAWN_DURATION_MS = 3000L
    const val WAKE_DURATION_MS = 1500L
    const val DEEP_SLEEP_TIMEOUT_MS = 600000L

    fun svgForState(state: ClawdState): String {
        return STATE_SVG[state] ?: STATE_SVG[ClawdState.IDLE]!!
    }

    fun svgForWorking(activeSessionCount: Int): String {
        for (tier in WORKING_TIERS) {
            if (activeSessionCount >= tier.minSessions) return tier.file
        }
        return WORKING_TIERS.last().file
    }

    fun svgForJuggling(activeSessionCount: Int): String {
        for (tier in JUGGLING_TIERS) {
            if (activeSessionCount >= tier.minSessions) return tier.file
        }
        return JUGGLING_TIERS.last().file
    }

    fun resolveDisplayHint(hintFilename: String?): String? {
        if (hintFilename == null) return null
        return DISPLAY_HINT_MAP[hintFilename]
    }

    fun resolveSvg(
        state: ClawdState,
        activeSessionCount: Int = 1,
        displayHint: String? = null
    ): String {
        val hinted = resolveDisplayHint(displayHint)
        if (hinted != null) return hinted

        return when (state) {
            ClawdState.WORKING -> svgForWorking(activeSessionCount)
            ClawdState.JUGGLING -> svgForJuggling(activeSessionCount)
            else -> svgForState(state)
        }
    }

    fun minDisplayMs(state: ClawdState): Long = MIN_DISPLAY_MS[state] ?: 0L
    fun autoReturnMs(state: ClawdState): Long = AUTO_RETURN_MS[state] ?: 0L
}

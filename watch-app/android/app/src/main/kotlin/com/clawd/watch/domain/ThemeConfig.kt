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

    private data class TierEntry(val minSessions: Int, val file: String)

    private val WORKING_TIERS = listOf(
        TierEntry(3, "clawd-working-building.svg"),
        TierEntry(2, "clawd-headphones-groove.svg"),
        TierEntry(1, "clawd-working-typing.svg")
    )

    private val JUGGLING_TIERS = listOf(
        TierEntry(2, "clawd-working-juggling.svg"),
        TierEntry(1, "clawd-headphones-groove.svg")
    )

    fun resolveSvg(
        state: ClawdState,
        activeSessionCount: Int = 1,
        displayHint: String? = null
    ): String {
        return when (state) {
            ClawdState.WORKING -> tierSvg(WORKING_TIERS, activeSessionCount)
            ClawdState.JUGGLING -> tierSvg(JUGGLING_TIERS, activeSessionCount)
            else -> STATE_SVG[state] ?: STATE_SVG[ClawdState.IDLE]!!
        }
    }

    private fun tierSvg(tiers: List<TierEntry>, count: Int): String {
        for (tier in tiers) {
            if (count >= tier.minSessions) return tier.file
        }
        return tiers.last().file
    }
}

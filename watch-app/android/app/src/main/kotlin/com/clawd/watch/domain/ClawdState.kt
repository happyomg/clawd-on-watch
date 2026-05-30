package com.clawd.watch.domain

enum class ClawdState(
    val priority: Int,
    val isOneshot: Boolean,
    val isSleepSequence: Boolean = false
) {
    ERROR(8, true),
    NOTIFICATION(7, true),
    SWEEPING(6, true),
    ATTENTION(5, true),
    CARRYING(4, true),
    JUGGLING(4, false),
    WORKING(3, false),
    THINKING(2, false),
    IDLE(1, false),
    SLEEPING(0, false),

    YAWNING(-1, false, isSleepSequence = true),
    DOZING(-1, false, isSleepSequence = true),
    COLLAPSING(-1, false, isSleepSequence = true),
    WAKING(-1, false, isSleepSequence = true);

    companion object {
        private val byName = entries.associateBy { it.name.lowercase() }

        fun fromString(name: String): ClawdState? = byName[name.lowercase()]

        fun fromStringOrIdle(name: String): ClawdState = fromString(name) ?: IDLE

        val ONESHOT_STATES: Set<ClawdState> = entries.filter { it.isOneshot }.toSet()

        val SLEEP_SEQUENCE = listOf(YAWNING, DOZING, COLLAPSING, SLEEPING)

        val PRIORITIZED: List<ClawdState> =
            entries.filter { it.priority >= 0 }.sortedByDescending { it.priority }
    }
}

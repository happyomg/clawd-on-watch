package com.clawd.watch.domain

enum class ClawdState {
    ERROR, NOTIFICATION, SWEEPING, ATTENTION, CARRYING,
    JUGGLING, WORKING, THINKING, IDLE, SLEEPING,
    YAWNING, DOZING, COLLAPSING, WAKING;

    companion object {
        private val byName = entries.associateBy { it.name.lowercase() }
        fun fromString(name: String): ClawdState? = byName[name.lowercase()]
        fun fromStringOrIdle(name: String): ClawdState = fromString(name) ?: IDLE
    }
}

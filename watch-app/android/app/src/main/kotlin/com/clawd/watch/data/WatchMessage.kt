package com.clawd.watch.data

import org.json.JSONObject

sealed class WatchMessage {

    /**
     * Compact state pushed by the desktop: {"s":"working","svg":"clawd-working-typing.svg","n":2}
     * The watch renders this directly — no local state management.
     */
    data class CompactState(
        val state: String,
        val svg: String?,
        val activeCount: Int
    ) : WatchMessage()

    data class ApprovalRequest(
        val requestId: String,
        val sessionId: String,
        val tool: String,
        val command: String,
        val risk: String,
        val timeoutMs: Long?,
        val expiresAt: Long?
    ) : WatchMessage()

    companion object {
        fun parse(json: JSONObject): WatchMessage? {
            // Compact state: {"s":"working","svg":"...","n":2}
            if (json.has("s")) {
                return CompactState(
                    state = json.getString("s"),
                    svg = if (json.isNull("svg")) null else json.optString("svg", null),
                    activeCount = json.optInt("n", 0)
                )
            }

            // Approval request: {"type":"approval_request",...}
            return when (json.optString("type")) {
                "approval_request", "approval.request" -> ApprovalRequest(
                    requestId = json.getString("requestId"),
                    sessionId = json.optString("sessionId", ""),
                    tool = json.optString("tool", ""),
                    command = json.optString("command", ""),
                    risk = json.optString("risk", "medium"),
                    timeoutMs = if (json.has("timeoutMs")) json.getLong("timeoutMs") else null,
                    expiresAt = if (json.has("expiresAt")) json.getLong("expiresAt") else null
                )
                else -> null
            }
        }
    }
}

data class ApprovalResponse(
    val requestId: String,
    val decision: String,
    val source: String
)

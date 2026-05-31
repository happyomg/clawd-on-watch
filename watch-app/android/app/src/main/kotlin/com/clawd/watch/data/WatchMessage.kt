package com.clawd.watch.data

import org.json.JSONObject

sealed class WatchMessage {

    /**
     * Compact state pushed by the desktop: {"s":"working","n":2,"th":"79c952"}
     *
     * The payload is theme-agnostic — the desktop no longer dictates an SVG
     * filename. The watch resolves `state` + `activeCount` to a concrete asset
     * via its local theme manifest (see ThemeConfig). `themeHash` is the active
     * desktop theme fingerprint; when it differs from the watch's cached theme
     * the watch knows it is rendering a stale theme and a sync is due.
     *
     * `svg` is retained only to parse legacy desktop builds that still send a
     * filename; current builds omit it and the watch ignores it.
     */
    data class CompactState(
        val state: String,
        val activeCount: Int,
        val themeHash: String?,
        val svg: String? = null
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
            // Compact state: {"s":"working","n":2,"th":"79c952"}
            if (json.has("s")) {
                return CompactState(
                    state = json.getString("s"),
                    activeCount = json.optInt("n", 0),
                    themeHash = if (json.isNull("th")) null else json.optString("th"),
                    svg = if (json.isNull("svg")) null else json.optString("svg")
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

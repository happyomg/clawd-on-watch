package com.clawd.watch.data

import org.json.JSONObject

sealed class WatchMessage {

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
        val expiresAt: Long?,
        val questions: List<Question>?
    ) : WatchMessage() {
        data class Question(val text: String, val options: List<String>)
    }

    companion object {
        fun parse(json: JSONObject): WatchMessage? {
            if (json.has("s")) {
                return CompactState(
                    state = json.getString("s"),
                    activeCount = json.optInt("n", 0),
                    themeHash = if (json.isNull("th")) null else json.optString("th"),
                    svg = if (json.isNull("svg")) null else json.optString("svg")
                )
            }

            return when (json.optString("type")) {
                "approval_request", "approval.request" -> {
                    val questions = if (json.has("questions")) {
                        val arr = json.getJSONArray("questions")
                        (0 until arr.length()).map { i ->
                            val qObj = arr.getJSONObject(i)
                            val opts = if (qObj.has("opts")) {
                                val oArr = qObj.getJSONArray("opts")
                                (0 until oArr.length()).map { j -> oArr.getString(j) }
                            } else emptyList()
                            ApprovalRequest.Question(qObj.optString("q", ""), opts)
                        }
                    } else null
                    ApprovalRequest(
                        requestId = json.getString("requestId"),
                        sessionId = json.optString("sessionId", ""),
                        tool = json.optString("tool", ""),
                        command = json.optString("command", ""),
                        risk = json.optString("risk", "medium"),
                        timeoutMs = if (json.has("timeoutMs")) json.getLong("timeoutMs") else null,
                        expiresAt = if (json.has("expiresAt")) json.getLong("expiresAt") else null,
                        questions = questions
                    )
                }
                else -> null
            }
        }
    }
}

data class ApprovalResponse(
    val requestId: String,
    val decision: String,
    val source: String,
    val answers: Map<String, String>? = null
)

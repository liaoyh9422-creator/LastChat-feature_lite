package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

@Serializable
enum class ToolApprovalScope {
    Once,
    Conversation,
    Always,
}

@Serializable
enum class ToolApprovalStoredDecision {
    Ask,
    Allow,
    Deny,
}

data class ToolApprovalResponse(
    val approved: Boolean,
    val scope: ToolApprovalScope = ToolApprovalScope.Once,
)

data class ToolApprovalRequest(
    val conversationId: Uuid,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonElement,
)

fun interface ToolApprovalHandler {
    suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalResponse
}

data class AskUserRequest(
    val conversationId: Uuid,
    val toolCallId: String,
    val question: String,
    val options: List<String>,
)

fun interface AskUserHandler {
    suspend fun askUser(request: AskUserRequest): String
}


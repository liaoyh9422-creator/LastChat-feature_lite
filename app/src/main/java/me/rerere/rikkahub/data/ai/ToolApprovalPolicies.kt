package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

private const val TOOL_APPROVAL_POLICY_PREFIX = "tool:"
private const val TOOL_APPROVAL_POLICY_CONVERSATION_PREFIX = "conversation:"

fun toolApprovalPersistentPolicyKey(toolName: String): String {
    return TOOL_APPROVAL_POLICY_PREFIX + toolName.trim()
}

fun toolApprovalConversationPolicyKey(conversationId: Uuid, toolName: String): String {
    return TOOL_APPROVAL_POLICY_CONVERSATION_PREFIX + conversationId.toString() + ":" + toolName.trim()
}

fun Settings.getToolApprovalPersistentDecision(toolName: String): ToolApprovalStoredDecision {
    return toolApprovalPersistentPolicies[toolApprovalPersistentPolicyKey(toolName)] ?: ToolApprovalStoredDecision.Ask
}

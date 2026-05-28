package com.sprintstart.sprintstartbackend.chat.models

/**
 * Used to determine who actually provided this text in a chat.
 */
internal enum class ChatRole {
    AI, // Answers from the AI
    USER, // Prompts from the user
    SYSTEM, // System prompts fine-tuning AI behaviour
    ORCHESTRATOR, // idk...
}

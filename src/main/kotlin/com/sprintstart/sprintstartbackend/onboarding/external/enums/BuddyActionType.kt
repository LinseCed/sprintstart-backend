package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * An action the buddy agent may *propose* and, on the hire's explicit confirmation, perform.
 *
 * Each wraps an existing `/me/...` operation with the same caller-scoping. The [toolName] is the
 * name the AI reasoner calls; the [label] is the button the hire confirms. A proposal never mutates
 * — only the confirm round-trip does — so the two are deliberately the same catalog, read at both
 * ends, and can never drift apart.
 */
enum class BuddyActionType(
    val toolName: String,
    val label: String,
) {
    FLAG_TO_PM("flag_to_pm", "Flag this to your PM"),
    CLAIM_TASK_ZERO("claim_task_zero", "Start Task 0"),
    OPEN_ORIENTATION("open_orientation", "Open the task packet"),
    CLAIM_GOAL("claim_goal", "Work toward this task"),
    SUBMIT_VERIFICATION("submit_verification", "Submit this answer"),
    ;

    companion object {
        fun fromToolName(toolName: String): BuddyActionType? = entries.firstOrNull { it.toolName == toolName }
    }
}

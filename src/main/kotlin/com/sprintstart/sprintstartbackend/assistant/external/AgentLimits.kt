package com.sprintstart.sprintstartbackend.assistant.external

/**
 * How many backend-tool round trips a turn may take before it answers with what it has; both assistants'
 * loops share the budget.
 */
const val MAX_AGENT_STEPS = 5

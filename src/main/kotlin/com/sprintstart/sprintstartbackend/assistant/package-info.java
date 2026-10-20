/**
 * The agent protocol both assistants run: agent DTOs, the stream vocabulary and its relay, and the life of
 * a proposed change. The kernel of the assistants: it depends on shared, plus user's deletion event so it
 * erases the proposals it stores (P5).
 */
@ApplicationModule(
        allowedDependencies = {"shared :: ids", "shared :: model", "shared :: ai", "user :: api-events"}
)
package com.sprintstart.sprintstartbackend.assistant;

import org.springframework.modulith.ApplicationModule;

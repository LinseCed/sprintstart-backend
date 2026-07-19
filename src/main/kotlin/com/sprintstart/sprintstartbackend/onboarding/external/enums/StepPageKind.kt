package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The kind of a page within a learn-verify step/module.
 *
 * A step is presented to the hire as an ordered sequence of pages (a stepper): grounded lesson(s),
 * optional hands-on practice, and the graded check. Today the set is fixed and derived from a
 * step's existing shape (its `content`, `tasks`, and configured `Verification`); the enum is the
 * stable contract so persisted, author-defined multi-page modules can slot in later without the
 * client changing.
 */
enum class StepPageKind {
    /** A grounded lesson to read. Carries the lesson body. */
    LESSON,

    /** Hands-on practice tasks to work through before the check. */
    TASK,

    /** The graded check. References the step's `Verification`; the client submits via the
     *  existing verification-attempt endpoints. */
    VERIFY,
}

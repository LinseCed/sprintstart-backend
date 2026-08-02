package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The arrival steps the system can check for itself, rather than taking the hire's word for.
 *
 * ### Why this is a closed catalog and not a field on the row
 *
 * A derivation is code: something has to know *how* to observe "you have a GitHub account". So a
 * derived step cannot be authored freely — it can only be one of these, and the step's own
 * [stepKey] is what binds a row to its derivation. There is no column pointing at a deriver,
 * because a column could name one that does not exist.
 *
 * Nothing here is seeded. An admin adds the ones their organisation actually wants from the
 * authoring surface, which is also what keeps a derived step off the boards of people it makes no
 * sense for — a delivery lead does not need a local build.
 *
 * ### Observing settles; failing to observe never refutes
 *
 * Every derivation answers *"can I see that this is done?"*, and **no** is always
 * "not that I can see", never "no, and you are wrong to say otherwise". That is why
 * [selfConfirmable] exists per derivation rather than following from being derived at all.
 */
enum class ArrivalDerivation(
    val stepKey: String,
    val suggestedTitle: String,
    val suggestedDescription: String,
    val selfConfirmable: Boolean,
) {
    /**
     * The hire has declared a GitHub login, and GitHub confirms an account with that name exists.
     *
     * Worth checking rather than trusting, because this value is what artifact verification
     * compares a pull request's author against. A typo does not fail loudly — it silently stops
     * crediting work the hire really did, and leaves them reading as calm rather than blocked.
     *
     * **Not self-confirmable.** The check is definitive when it answers at all, and letting
     * somebody tick it would let them declare away the one fact their work being credited depends
     * on.
     */
    GITHUB_ACCOUNT(
        stepKey = "github-account",
        suggestedTitle = "Add your GitHub username",
        suggestedDescription =
            "So work you push can be recognised as yours. Add it in Settings, or from this card.",
        selfConfirmable = false,
    ),

    /**
     * The hire has produced work on a project, which means their environment evidently runs.
     *
     * The evidence half of what `EnvironmentReadiness` used to do, without the second record of it:
     * a contribution already proves the environment worked, so storing readiness separately would
     * be a second copy of a fact that lives somewhere durable — the copy that goes stale.
     *
     * ⚠️ **Self-confirmable, and that is the important half.** This evidence arrives *late* — by the
     * time somebody has opened a pull request, getting set up is days behind them. So the hire
     * saying "it builds" is the answer that actually lands on day one, and the derivation is a
     * backstop that closes the step for anybody who never got round to ticking it.
     */
    ENVIRONMENT_READY(
        stepKey = "environment-ready",
        suggestedTitle = "Get the project running on your machine",
        suggestedDescription =
            "Clone it, install what it needs, and run the build once. Stuck? Ask your buddy.",
        selfConfirmable = true,
    ),
    ;

    companion object {
        fun forStepKey(stepKey: String): ArrivalDerivation? = entries.firstOrNull { it.stepKey == stepKey }
    }
}

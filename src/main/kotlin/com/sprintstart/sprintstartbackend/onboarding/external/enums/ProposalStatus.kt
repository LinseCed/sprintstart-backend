package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * Whether a
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal] is work a
 * hire can be pointed at.
 *
 * [LIVE] is claimable; [REJECTED] is terminal **and sticky** — a task somebody turned down is never
 * mined back into existence, or they would turn it down again after every crawl.
 *
 * ### `PROPOSED` is gone, and that is the point
 *
 * A mined task used to land as `PROPOSED` and wait for a PM to approve it before any hire could see
 * it, which made a person's attention a *gate*: nothing reached a hire until somebody worked through
 * a queue. Mined tasks are now live on arrival and carry
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal.reviewed]
 * instead, and `StarterWorkMatcher` **demotes** the unreviewed ones. Human attention improves the
 * ranking rather than blocking the pool — see `forks/SKILL_MAP_RETIREMENT_DESIGN.md`, D1.
 *
 * The enum used to serve competency and edge proposals and per-item blueprint review too. Those are
 * all gone — competencies generate themselves and the baseline was retired — so starter work is the
 * one lifecycle left, and it is no longer a review lifecycle at all.
 */
enum class ProposalStatus { LIVE, REJECTED }

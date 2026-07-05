# Issue Agent

Use this mode when creating or refining a GitHub issue locally before adding it to GitHub.

## Goal

Create a clear, concise GitHub issue draft that describes what needs to change and why.

The issue agent does not create implementation plans and does not inspect the repository unless explicitly asked. Its purpose is to write a ticket that can be copied into GitHub or later used by the planning agent.

## Source and Output Locations

* Store issue drafts in `./docs/tickets/`.
* Use consistent file names that include the issue number when available.
* If no issue number exists yet, use a short kebab-case title.
* Do not write implementation plans.
* Do not write ADRs.
* Do not modify production code.

## When to use this mode

Use issue mode when the user asks to:

* create a GitHub issue
* write a local ticket
* turn an idea into an issue
* refine an existing issue
* prepare a ticket before planning
* split a broad idea into smaller issues

Example commands:

```text
[agent: issue]
Create an issue for adding project roles
```

```text
[agent: issue]
Write a GitHub issue from this idea:
<pasted notes>
```

```text
[agent: issue]
Split this feature into smaller GitHub issues:
<pasted feature description>
```

## General behavior

* Follow the root `AGENTS.md` rules in addition to this file.
* Do not write production code.
* Do not create an implementation plan.
* Ask clarifying questions when the requested behavior is unclear.
* Do not create or save the final issue draft until all necessary clarifying questions have been answered and the user has confirmed the resolved understanding.
* Prefer a concise issue over a long design document.
* Do not silently invent requirements.
* If information is missing, ask about it before creating the final issue. Use `Open questions` only during clarification, or in a best-effort issue explicitly requested by the user.
* If the request is too broad, suggest smaller issues.
* Keep the issue focused on observable behavior and acceptance criteria.
* Avoid implementation details unless they are explicitly part of the requirement.

## Communication while drafting issues

Narrate the drafting work concisely.

Before writing the issue, summarize the understood request in one or two sentences.

If the request is ambiguous, ask focused clarification questions. If the user wants a best-effort draft anyway, record unclear parts under `Open questions` instead of inventing requirements.

If the request is too broad, suggest a smaller first issue and list possible follow-up issues.

## Clarification and confirmation workflow

The issue agent must clarify first and write the final issue second.

Use this workflow whenever the idea or request leaves any relevant behavior, scope, acceptance criterion, user-facing outcome, constraint, dependency, or out-of-scope boundary unclear:

1. Summarize the understood request in one or two sentences.
2. Ask a focused list of clarifying questions before writing the issue draft.
3. Wait for the user's answers.
4. Repeat clarification if the answers introduce new ambiguity.
5. Once the issue is clear, present a `Confirmed understanding` summary containing:
   * confirmed goal
   * confirmed scope
   * confirmed out-of-scope items
   * confirmed acceptance criteria
   * remaining risks, if any
6. Ask the user to confirm that this understanding is correct.
7. Only after the user confirms, create and save the final issue draft in `./docs/tickets/`.

Do not create a final issue draft and then place clarifying questions at the end. Questions must be resolved before the final issue is written.

If the user explicitly says to proceed with a best-effort draft without clarification, the agent may create the issue, but it must clearly mark unresolved items under `Open questions`.

If there are no meaningful open questions after reading the request, the agent may create the issue draft directly. Still distinguish confirmed facts from assumptions.

## GitHub issue drafting

When creating an issue draft, focus on:

* the problem
* the goal
* expected behavior
* acceptance criteria
* relevant constraints
* open questions

Do not include:

* detailed implementation steps
* exact file paths unless already known and relevant
* code snippets
* ADR decisions
* detailed test plans
* repository inspection summaries

Those belong to the planning agent.

## Splitting broad requests

When the user asks to split a broad idea into several GitHub issues:

1. Identify the smallest valuable first slice.
2. Create separate issue drafts for independent pieces of work.
3. Keep each issue focused on one clear behavior or outcome.
4. Mark dependencies between issues when relevant.
5. Avoid creating issues that are only technical cleanup unless the user explicitly wants that.

## Required output

After required questions have been answered and the user has confirmed the resolved understanding, create the issue draft using this format:

```md
# Issue: <issue title>

## Summary

Briefly describe what should be changed.

## User Story

Describe the feature from the user's perspective.

## Context & Motivation

Explain why this change is needed.

## Desired behavior

Describe the expected behavior after the change.

## Scope

List what is included in this issue.

## Out of scope

List related work that should not be done as part of this issue.

## Acceptance criteria

- [ ] First concrete condition that must be true.
- [ ] Second concrete condition that must be true.
- [ ] Third concrete condition that must be true.

## Open questions

List missing or ambiguous requirements.

If there are no open questions, write:

None.

## Notes

Add relevant context, constraints, or links.

If there are no notes, write:

None.
```

## Output rules

* Keep the issue concise.
* Do not include implementation steps.
* Do not include detailed architecture discussion.
* Do not claim that files were inspected unless they were actually inspected.
* Distinguish confirmed requirements from assumptions.
* Do not save issue drafts under `./docs/tickets/` until clarification is complete and the user has confirmed the resolved understanding.
* Save issue drafts under `./docs/tickets/` only after confirmation.

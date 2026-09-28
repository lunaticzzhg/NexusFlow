---
name: nexusflow-architecture-first-delivery
description: Design a NexusFlow App, Backend, Contracts, or AI flow from current ownership and authoritative facts before deriving files or an implementation plan. Use when the user asks to see or confirm architecture first, or a proposed solution has unresolved structural boundaries.
---

# NexusFlow Architecture First Delivery

## Purpose and authority

Help a maintainer understand how a proposed flow works before turning it into artifacts. This skill governs the **delivery order** of an architecture discussion; it does not replace `AGENTS.md`, the touched architecture authority, `nexusflow-feature-development`, or an independent architecture decision required by repository governance.

Read `AGENTS.md`, `.agents/skills/INDEX.md`, the authorities for the affected App / Backend / AI areas, real contracts when involved, and the direct source, callers, and tests. Start with the user intent and observable outcome. Search the current repository for the same responsibility before proposing a new owner. Treat product requirements and backend durable facts as authority; mark unconfirmed protocol or product facts as unknown.

Use this skill for an explicit “先看架构 / 确认后再出方案” request, or when architecture judgment is needed before a landing plan. Pure copy, styling, mechanical wiring, and a prescribed local fix with no ownership choice do not need an Architecture Brief.

## Evidence before the brief

- Reconstruct the current entry, decision and writable state owners, lifecycle, terminal behavior, effect boundary, and first useful debug checkpoint. Check adjacent mature implementations and their tests, composition root, and applicable navigation or API boundary.
- For a proposed page, route, destination, target, API, or state holder, find the existing product-semantic owner. Reuse or extend it when semantics match; a wire field or entry source alone does not establish a new product concept.
- For a recovery state, identify the authoritative producer, query path, and evidence that the state is reachable. Distinguish durable business facts from temporary input and experience cache. Do not promote a sequence of client calls into a durable partial state without a confirmed contract.
- When paths branch and later meet, distinguish genuinely separate owners/lifecycles from different entry sources. Name the business fact that allows them to converge; share the existing action where its semantics are the same.
- Compare the proposed boundary against the smallest existing implementation. A diagram node is a conceptual responsibility until a real current need justifies an artifact.

## Architecture Brief before Landing Plan

When the user explicitly asks to review architecture first, deliver **only** the Architecture Brief and wait for their architectural feedback. Do not include a file list, thin slices, endpoint/DTO design, test commands, or implementation sequence in that response. If the user has already authorized planning or implementation and has not asked for a separate review, use the same architecture checks and continue the authorized work without requiring another confirmation. Missing facts that determine business semantics still require clarification.

Keep the brief sized to the problem, normally:

1. One sentence stating what stays, what changes, why, and the observable success condition.
2. One small responsibility diagram using real components or confirmed new responsibilities. Label conceptual/inline boundaries and unknowns; a node does not imply a class, file, or binding. Solid arrows must represent an actual or proposed call, data flow, or lifecycle handoff, with current and proposed relationships distinguished.
3. One concrete path from input to authoritative result and outward output.
4. Up to three decisions that explain ownership, lifecycle, convergence, or trust boundaries. Include only relevant failure/cancel/recovery and unknown facts, with the terminal and first debug checkpoint.

Place evidence beside each consequential claim and distinguish current source behavior from the proposal or inference. Use an additional sequence/state diagram only when timing or transitions cannot be explained by the main diagram and short flow. Do not turn the evidence checklist into the response structure.

## Independent decision boundary

NexusFlow requires an independent Architect for cross-owner architecture, unclear owner/lifecycle, durable workflow or state machine, complex recovery/duplicate/late-result behavior, and Human Traceability structural decisions. In those cases, use `nexusflow-ai-handoff` with the External Architect task contract and self-contained `WORK_ORDER.md` required by `AGENTS.md`. The current implementer may reconstruct facts and frame the decision, but must not self-approve the target ownership. If the user requested architecture only, present the independent architecture conclusion as the Step 1 brief and hold the detailed Work Order / landing plan until that review is complete. Follow an already approved Work Order through `orbit-work-order-executor`.

## Landing Plan after the architecture boundary is settled

Derive only the artifacts needed to realize the agreed responsibilities. For each new file, class, interface, route, DTO, table, or binding, ask what current responsibility it owns; whether an existing owner can express it; and what correctness or traceability would be lost if it were inlined. Remove pass-through wrappers, empty adapters, unused modes or source labels, parallel product concepts, and placeholders for unconfirmed contracts.

The plan should identify the touched areas, each artifact's responsibility, concepts intentionally kept inline, real contracts and deferred facts, smallest vertical slices, scope-derived verification, non-goals, and a simplification or rollback point. Route implementation through `nexusflow-feature-development`, or through `orbit-work-order-executor` when executing an external Work Order. If implementation reveals a conflict with confirmed ownership, report a deviation instead of adding a parallel route or owner.

## Reader check

Before delivery, check that a maintainer can find the entry, decision, writable state, terminal, and first debug checkpoint without reading a long inventory. The brief must distinguish existing behavior, proposed behavior, and unresolved facts. Reassess an intentionally deferred boundary when a named product path, contract, or second real caller appears; do not build it in advance.

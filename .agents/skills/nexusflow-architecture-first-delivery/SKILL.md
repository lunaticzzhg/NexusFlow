---
name: nexusflow-architecture-first-delivery
description: Deliver NexusFlow architecture design first, then a concrete implementation plan derived from it. Use when the user asks to see or confirm architecture before planning, or when a proposed solution has unresolved structural boundaries.
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

## Two-step delivery

The deliverables are distinct:

1. **Architecture Design:** establish the target responsibilities, dependencies, authority, lifecycle, and user-visible outcomes. Include both an architecture diagram and a flowchart as defined below. Do not include a file list, endpoint/DTO design, test commands, or implementation sequence.
2. **Landing Plan:** after the architecture boundary is established, derive concrete repository changes and verification from that design. Do not replace the plan with another conceptual architecture description.

When the user explicitly asks to see or confirm architecture first, deliver step 1 alone and wait for their architectural feedback before step 2. When the user asks for the plan after architecture has been agreed, deliver step 2 directly. If the user has already authorized planning or implementation without requesting a separate architecture review, perform both checks in order within the work; do not impose an extra approval gate. Missing facts that determine business semantics still require clarification.

### Step 1: Architecture Design

Keep the design sized to the problem, normally:

1. One sentence stating what stays, what changes, why, and the observable success condition.
2. A **traditional architecture diagram**: use nested or adjacent boxes to group stable components by execution environment and ownership boundary. Name each component's responsibility and authoritative state, using real owners or clearly marked conceptual responsibilities; a box does not require a new class or service. Do not connect components with arrows or draw a left-to-right/top-to-bottom chain, even when the arrows are labeled as dependencies. State dependency direction and current-versus-proposed relationships in short prose below the diagram.
3. A separate **flowchart** for one representative user request: show entry, meaningful decisions, effects, success and relevant failure/recovery terminals, and outward output. Arrows here mean execution order. Include the first useful debug checkpoint at the boundary where a failure can be localized.
4. Up to three decisions that explain ownership, lifecycle, convergence, or trust boundaries, with evidence beside consequential claims.

Give each diagram its own title and legend. Architecture answers "who owns what"; the short text below it answers "what depends on what". Flowchart answers "what happens next in this request". The architecture diagram must contain boxes and containment only: no arrows, ordered steps, decision diamonds, success/failure states, or action labels. Calls, retries, logging events, decisions, and terminals belong only in the flowchart. Keep both diagrams small enough to read; collapse routine details and describe them in prose. If the request spans distinct success and recovery flows, use one flowchart with branches rather than duplicating the architecture diagram. Distinguish current source behavior from proposal or inference. Do not turn the evidence checklist into the response structure.

## Independent decision boundary

NexusFlow requires an independent Architect for cross-owner architecture, unclear owner/lifecycle, durable workflow or state machine, complex recovery/duplicate/late-result behavior, and Human Traceability structural decisions. In those cases, use `nexusflow-ai-handoff` with the External Architect task contract and self-contained `WORK_ORDER.md` required by `AGENTS.md`. The current implementer may reconstruct facts and frame the decision, but must not self-approve the target ownership. If the user requested architecture only, present the independent architecture conclusion as the Step 1 brief and hold the detailed Work Order / landing plan until that review is complete. Follow an already approved Work Order through `orbit-work-order-executor`.

### Step 2: Landing Plan

Derive only the artifacts needed to realize the agreed responsibilities. For each new file, class, interface, route, DTO, table, or binding, ask what current responsibility it owns; whether an existing owner can express it; and what correctness or traceability would be lost if it were inlined. Remove pass-through wrappers, empty adapters, unused modes or source labels, parallel product concepts, and placeholders for unconfirmed contracts.

The plan must be specific enough that an implementer can act without inventing the design: name the existing files or precise owner locations to change, what each change does, how callers and producers/consumers are affected, and what remains unchanged. For each slice, give the behavior it delivers, affected files or modules, and the narrow verification that proves it. Identify contract/compatibility and migration effects when real, failure/retry/logging behavior when relevant, non-goals, unresolved facts, and a simplification or rollback point. State why each new artifact is needed; prefer extending the proven owner. Avoid speculative file lists or tests that merely mirror implementation.

Trace each planned change back to a step 1 owner, flowchart decision, or terminal. If the plan exposes a new ownership decision, return to step 1 rather than quietly adding a component. Route implementation through `nexusflow-feature-development`, or through `orbit-work-order-executor` when executing an external Work Order. If implementation reveals a conflict with confirmed ownership, report a deviation instead of adding a parallel route or owner.

## Reader check

Before step 1 delivery, check that a maintainer can find the entry, decision, writable state, terminal, and first debug checkpoint without reading a long inventory. Read the architecture diagram alone: it must be possible to rearrange its boxes without changing its meaning; if not, execution order has leaked into it. Read the flowchart alone: if it only lists components without a decision and terminal, complete the flow. Before step 2 delivery, check that every consequential diagram node and flow branch has a concrete owner and verification, and that a reader can locate each proposed edit without searching the whole repository. Both steps must distinguish existing behavior, proposed behavior, and unresolved facts. Reassess an intentionally deferred boundary when a named product path, contract, or second real caller appears; do not build it in advance.

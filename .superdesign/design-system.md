# Orbit Prototype Design System

## Product intent

Orbit is a calm, trustworthy personal leisure-planning agent. It helps a user notice one worthwhile opportunity, negotiate a one-off plan, compare concrete trade-offs, approve visible side effects, and learn transparently from the outcome. The interface must feel proactive but never intrusive or opaque.

## Primary flow

`Discovery → visible task conditions → Plan A/B/C comparison → editable approval → action result → lightweight feedback → inspectable preference evidence`.

Every learned signal must clearly state whether it affects the current task, the next recommendations, or a proposed long-term preference. Never represent an inference as a user-confirmed fact.

## Visual language

- Android-first mobile shell, 390px-class viewport.
- Orbital dual-tone palette: the dark reference uses deep navy backgrounds, slate surfaces, off-white text, sky-blue information and warm-lime primary actions; the light reference uses a cool mist-white background, blue-grey surface layers, deep navy text, sky-blue information and the same warm-lime primary actions. Preserve the two references' hue relationship and hierarchy. For the light shell, do not use deep navy as a filled surface: use only cool mist-white, blue-grey and sky-blue surface layers, with deep blue reserved for text. Avoid unrelated accent colors and generic white-and-blue dashboard styling.
- Sans-serif system typography; no decorative or serif fonts.
- Rounded cards (16–20px), compact chips, 12–24px spacing rhythm, quiet borders, modest shadow only for sheets.
- Persistent bottom navigation. Approval and feedback use bottom sheets above the shell.
- Motion should be brief fades/slides that explain state change, never block the approval decision.

## Interaction rules

- Opportunity cards surface source, freshness, why-now and why-you signals.
- Current-task conditions are editable chips with an explicit origin label: user, opportunity, or suggested preference.
- Plan A is best match; B is easier; C is a clearly labelled exploratory option. Each must differ in at least one measurable trade-off.
- Approval lists each write action, editable fields, permission/freshness warnings, and independent action state.
- A feedback action should be one tap plus an optional reason, not a survey.
- Use clear content descriptions; never rely on color alone for status; keep targets at least 48dp.

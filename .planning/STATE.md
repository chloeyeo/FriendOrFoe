# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-10)

**Core value:** Point the phone at a drone and, within seconds and with no network, see a green ring or a red ring with a reason on that exact object — while the drone transmits nothing until asked, and its answer only works for the one observer who asked, at the place it is actually flying.
**Current focus:** Phase 1 — Foundation (H0–1)

## Current Position

Phase: 1 of 7 (Foundation)
Plan: 0 of TBD in current phase
Status: Ready to plan
Last activity: 2026-10-10 — Roadmap created (7 phases = the hour-plan milestones; 54/54 v1 requirements mapped; research flags from FEATURES, ARCHITECTURE, STACK and PITFALLS folded in)

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: - min
- Total execution time: 0.0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: -
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Phases are the user's hour-plan milestones (H0–1, H1–4, H4–7, H7–10, H10–12, H14–17, H17–20); success criteria are ordered runnable-first within each phase ("working prototype ASAP").
- [Roadmap]: Cut order fixed by the user: DET-08 → DET-09 → IFF-06/RSP-07 → EVL-01/02 → PHY-02; the JDG-02/03 dashboard stays in v1 although research recommends cutting it first.
- [Roadmap]: H4 hard checkpoint — if ClikaRtDetector does not run on the phone, flip `Detectors.create` to HttpDetector and keep building; the Phase 6 buffer re-runs the on-device checklist before the HTTP path is accepted for the demo. PIT-04 failure → change the prop first; only then insert Phase 2.1 (OWLv2, benched first) and cut EVL-01/02.
- [Phase 1 ahead]: Phone B hosts the hotspot (RSSI is readable only for the attached AP); the QR carries raw response bytes (byte mode, version ≤ 4); the class map is keyed on labelId (0, 4, 14, 33); same debug-signed build for the whole hackathon (ANDROID_ID license binding).
- [Phase 3 ahead]: FOF-1 still pending the user's confirmation — ARCHITECTURE.md keeps PROJECT.md's derivation (cmac under dayKey, tag today±1) and flags that a day-key holder can derive every drone's key; PITFALLS.md adds deriving the day from the challenge's `t` in UTC and a 120 s responder skew window.

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 1]: The offline ClikaRT license must be proven on phone A in airplane mode (not only on the laptop) before Phase 2 — an online credential fails with LICENSE_SERVER_UNREACHABLE and the license binds to ANDROID_ID.
- [Phase 1]: Hotspot + airplane-mode coexistence and `adb push` into `Android/data` are OEM-specific (LOW confidence) — test at H0; fallbacks are in ROADMAP.md Phase 1 flags. No phone was attached at init time.
- [Phase 1 decisions, H0]: research files disagree and the planner must pick once — CameraX 1.4.1 (FEATURES/ARCHITECTURE) vs 1.6.2 (STACK/PITFALLS); `:iff` JVM target 11 (STACK, verified) vs 17 (PITFALLS); test JSON via kotlinx-serialization 1.9.0 vs `org.json` testImplementation; port numbers (ARCHITECTURE 8000/8080/8129 vs STACK 8000/8129/8130).
- [Phase 3 decision]: near-field (< 30 m passes without bearing, PROJECT.md) vs configured 100 m Honest/Relay positions that exercise the cone (PITFALLS #30); width of `t` (u32 vs u64).
- [Phase 5]: QR readability distance is an estimate (0.6 m decodes, 1.0 m borderline at 1920 px) — measure on the day; demo at ≤ 0.7 m.
- [Phase 6]: OWLv2 on the phone is likely tens of seconds per frame (≈10 s on the laptop CPU) — DET-09 on device is a bench-first experiment; the accuracy option realistically runs through the laptop serve.
- [Research]: research/SUMMARY.md did not exist when the roadmap was written — read it when planning Phase 1 and fold any new flags into the phase plans.
- [Requirements]: REQUIREMENTS.md counted 59 v1 requirements; 54 IDs are actually defined and all 54 are mapped. Counts corrected in the Traceability section.

## Deferred Items

Items acknowledged and carried forward from previous milestone close:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| *(none)* | | | |

## Session Continuity

Last session: 2026-10-10 (roadmap creation)
Stopped at: ROADMAP.md and STATE.md written; REQUIREMENTS.md traceability filled. Next: `/gsd:plan-phase 1`
Resume file: None

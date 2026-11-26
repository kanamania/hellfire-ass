# Audit progress

Audit target: `main @ 85f5ae5` (2026-11-26). Brief: v2 audit prompt, with ten corrections
agreed in the 2026-11-26 session. The owner then asked for autonomous continuation
and for found gaps to be implemented.

## Covered

| Phase | State | Where |
|---|---|---|
| Host checks (Android, relay, worker, web API, dependency audits) | Done | `01-executive-summary.md` § Checks |
| A1: 22 known findings plus new defects | Done | `02-verified-defects.md` |
| A2: earlier recommendations | Done | `09-previous-recommendations.md` |
| B1–B6: core loop | Done | `03-feature-gap-matrix.md` (matrix and detailed findings) |
| B7: everyday scenarios | Done | `06-everyday-scenarios.md` |
| C1–C2: action engine, AI orchestration | Done | `03-feature-gap-matrix.md`, `04-architecture-review.md` |
| C3–C5: performance, reliability, security | Done | `05-performance-reliability-security.md` |
| D: multi-device | Done | `04-architecture-review.md` |
| E: secondary domains | Done | `10-not-now-and-play-appendix.md` |
| Backlog and roadmap | Done | `07-roadmap-and-backlog.md` |
| Evaluation plan | Done | `08-evaluation-plan.md` |

## Implementation

The backlog's implementation status is tracked in the **Status** column of
`07-roadmap-and-backlog.md`. Each implemented item is merged to `main` through its
own worktree branch with tests.

## Implemented so far (2026-11-26)

HF-001–HF-010, HF-012–HF-014, HF-020, HF-022, HF-025 and HF-030 are done; HF-011, HF-023 and HF-024 are partly done. Each one's status and commit are in `07-roadmap-and-backlog.md`. Android unit tests: 583, 0 failures, 5 skips.

Items waiting for an owner decision:
- HF-004b: amounts with cents.
- HF-011b: Done/Snooze buttons on reminder notifications (locked requirement 9).
- HF-040: auto-posting money.
- HF-060: Telegram.
- HF-066: Microsoft 365.
- HF-073: hosted CI.

## Next starting point

Take the first item in `07-roadmap-and-backlog.md` whose status is `OPEN`, in
P0 → P1 order. Skip items marked `OWNER DECISION`.

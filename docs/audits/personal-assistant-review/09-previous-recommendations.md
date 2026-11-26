# 09 — Earlier recommendations verified against the code

Audit target `main @ 85f5ae5`. `A/` = `android/app/src/main/java/com/hellfire/ass/`.


| # | Recommendation | Status | Evidence | Challenge / better approach |
|---|---|---|---|---|
| 1 | General-purpose autonomous workflow engine | **Missing; unnecessary/inappropriate** | No workflow code ("workflow" appears only in UI strings). Fixed typed commands: `OwnerCommand` = Remind, Remember, TaskAction, Draft, Cash, UseTemplate (`assistant/AssistantCommands.kt:24-55`). Action templates (`templates/TemplateData.kt`, `action_template`). Drawer suggestions are reviewed (`drawer/ActionEngine.kt`) | An autonomous engine conflicts with AGENTS.md "External sends/side effects must be user-approved". Extend the typed `OwnerCommand` and templates instead |
| 2 | Proactive heartbeat / event-driven intelligence | **Implemented but needs improvement** | VI: morning/evening brief scheduled once a day (`tasks/TaskScheduler.kt:208`; `DailyOutputsTest`); capture-gap alerts after capture maintenance (`processing/CaptureWork.kt:68`, `insights/CaptureGaps.kt`); escalating task alarms; news quiet-hour alerts (`news/NewsAlerts.kt`); event-driven `ProcessEventsWorker`; 20 WorkManager workers | Rule-based, which is correct. Gaps: OEM background survival (queued Priority 2, NOT TESTED). No shared attention budget across news, task, capture and guard notifications (PE) |
| 3 | Personal knowledge graph | **Partially implemented** | VI: `person_alias`, `person_contact`, cross-channel person keys (`insights/People.kt:76-88`; test "numbers and addresses from different channels resolve to one handle"); `memory_item` + `memory_revision`; `semantic_vector`; evidence links from every task, candidate or memory to `source_event` | A graph DB is unnecessary. Person-key plus source-evidence is the right shape. Add a typed link table only when a concrete query needs it |
| 4 | Unified AI tool calling & model orchestration | **Partially implemented (deliberately constrained)** | VI: one `AiRouter` with redaction, budget and an inference mutex (`ai/AiRouter.kt:18,39`). Schema-checked "tool" JSON → `ActionProposal.Task/Reply` (`ai/ActionProposals.kt:14-25`), which still need approval. Native tool calling disabled: `automaticToolCalling = false` (`ai/LocalModel.kt:125,159`); cloud `tool_calls` rejected (`ai/CloudProviders.kt:199`) | Keep proposals over autonomous loops. Orchestration is local-first with cloud opt-in. Fine for a single owner |
| 5 | Intelligent daily planning | **Partially implemented** | VI: `insights/Brief.kt` (141): tasks, bill forecast, waiting-on, debts, capture problems, calendar agenda (imports `CalendarAgenda`). Home "Needs you" / Review queue | A digest, not a planner. No slotting of tasks into free calendar time (PE, now feasible with the calendar slice) |
| 6 | Advanced communication intelligence | **Implemented but needs improvement** | VI: multi-account mail/WhatsApp, conversation grouping, FTS inbox search, waiting-on across channels (`People.kt`; test "waiting-on uses the latest message per person across channels"), approved drafts with per-account sending, AI Reply proposals. **UA:** I did not verify thread summarization or priority triage | Telegram is queued. Message dedupe between relay and notification for the same WhatsApp message: no evidence found (**IR**) |
| 7 | Personal project workspaces | **Missing** | No project entity. "project" hits are unrelated. `media_collection` / `inbox_item` are media/capture groupings | Probably unnecessary. Tags or collections on tasks and notes would cover it |
| 8 | Interactive multi-device ecosystem | **Partially implemented** | See Part D: plaintext mirror, news commands, encrypted library; no approvals; phone→server BLOCKED | Generalize the news-action command channel (D6) |
| 9 | Advanced financial forecasting | **Partially implemented** | VI: `insights/Recurring.kt` `detect` (≥3 consecutive months) and `BillForecast` (bills due vs balance → shortfall, `:10-20`) used by the brief (`Brief.kt:128`); category budgets with pace; balance check with visible assumptions (DEVICE_FEASIBILITY 2026-11-16) | Deterministic rules match AGENTS.md (integer ledger; LLM only for candidates). A month-ahead cash-flow projection from recurring income and bills is the next step. No ML |
| 10 | System-wide contextual capture | **Partially implemented** | VI: Share target (`ShareActivity.kt`, Sharesheet text/images/audio/video/PDF); 13 Glance widgets (`AndroidManifest.xml:172ff`); `VoiceInteractionService` (`:123`); in-app edge drawer. **M:** QS `TileService` (0 hits), `ACTION_PROCESS_TEXT` (0 hits), system overlay (README: "not implemented"; `player/OverlayEvaluation.kt` documents why) | QS tile and PROCESS_TEXT are cheap, high-value additions (PE). Do not add an overlay |
| 11 | Assistant intelligence evaluation framework | **Planned** | Only deterministic goldens (see T). DEVICE_FEASIBILITY plan item 4: "Expand English/Kiswahili model-quality datasets and cross-source/proactive workflow replays" | See T3 |
| 12 | Shared resource coordination | **Implemented but needs improvement (locally); unnecessary cross-device** | VI: `HeavyModelGate` single mutex (`ai/LocalModel.kt:184`, used by `SpeakerModel`, `LocalSpeech`, `HellFireApp.releaseIdleModels`); `OperatingProfile` INTERACTIVE/BALANCED/CONSERVE/DEFER (`performance/Performance.kt:20-55`); WorkManager unique work; microphone interruption handling (`audio/MicrophoneService.kt`). 17 independent `Mutex` objects | Adequate for one phone. Cross-device coordination (which device records or holds the mic) is unnecessary if the phone stays the only capturing device |

**What "Project review implementation — 2026-11-16" (DEVICE_FEASIBILITY l.660-746) covered** (v0.8.0, schema 16, 286 tests):
- **Ask:** finance filters with clarification; "Local model + saved sources" route (A2 #4).
- **Sources and history:** source-link labels; encrypted bounded Ask history.
- **Contacts:** same-name contact identities with merge/separation (#3).
- **Review:** Home Review queue (#5, #6).
- **Capture:** Android Sharesheet ingestion (#10).
- **Inbox:** grouping, search and waiting-on (#6).
- **Tasks:** recurrence, snooze and overdue profiles.
- **Recording:** Record workspace.
- **Money:** balance check with forecast assumptions (#9).
- **Language:** EN/SW selection.
- **Backup:** readiness and archive verify.
- **Web:** button contrast.
- **Build:** R8 release and a GitHub Actions workflow (not run on hosted CI).

It did not touch #1, #7, #11 or #12, and touched #8 only cosmetically.

---


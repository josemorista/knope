---
feature: gaoshun-task-cli
reviewed: task 5.0, working tree vs the index (task 4.0 staged, not committed) on feat/subtasks-and-transitions
diff-command: git diff -- src test
spec: .agents/tasks/gaoshun-task-cli/techspec.md
verdict: changes-requested (Warning resolved in the fix round)
findings: { blocker: 0, warning: 1, info: 3 }
axes: [spec, correctness, standards, alternatives]
axes-skipped: []
panel: [spec: sonnet, correctness: opus, standards: haiku, alternatives: sonnet]
---

# Review: task 5.0, status transitions

## Verdict

All 16 cells of the transition table, the eight messages, the illegal-move
error and the four commands match the spec, and the correctness pass found no
defect. One Warning: the no-ID path for the four commands has no test.

## Scope

- **Reviewed:** `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`,
  `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/logic/task.clj`, and the
  three matching test namespaces.
- **Set aside:** `.agents/tasks/gaoshun-task-cli/5_task.md` (spec artifact,
  checklist ticks only). Task 4.0 is staged in the index and was reviewed in
  `reviews/4_review.md`.

## Coverage

- **Axes run:**
  - spec: walked 5.1 to 5.4, the acceptance criteria and techspec
    "Transitions" both ways; nothing unauthorized.
  - correctness: every table cell, not-found and bad-id paths, `update!`
    parameter order, the `:unchanged` guard, and callers of the new functions.
  - standards: diplomat-architecture checklist, TDD placement, whole-map
    assertions.
  - alternatives: proportion and reuse.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the standards reviewer could not confirm
  red-before-green ordering from an uncommitted diff (the test writer's RED
  run showed 62 failures before implementation). The correctness reviewer
  could not confirm that babashka.cli passes `-1` through as a positional id;
  the main session's `bb test` run, where the bad-id test passes, settles it.
- **Not covered by this gate:** the test suite. CI runs it. The main session
  ran `bb test` before review: 167 tests, 484 assertions, 0 failures.

## Findings

### WARNING-1 — No test for a transition command without an ID
- **Axis:** spec-adherence
- **Location:** `src/gaoshun/diplomat/cli.clj` (`transition`, the `fallback`
  branch); `test/gaoshun/integration/cli_test.clj`
- **Problem:** `start`, `done`, `drop` or `reopen` with no argument prints the
  usage to stderr and exits 1 (a main-session decision, since `parse-id` would
  throw on nil). No test runs that path, so a regression back to a crash would
  pass the suite.
- **Evidence:** 5_task.md "Task tests" asks for end-to-end cases per command;
  `a-transition-with-a-bad-id-fails` always passes an argument.
- **Fix:** one integration test looping over the four commands with no
  argument, asserting `{:exit 1 :out "" :err <usage>}`.
- **Verified:** read `transition` in `diplomat/cli.clj` and grepped the
  integration tests; no run without an id exists.
- **Status:** resolved. `a-transition-without-an-id-prints-usage-and-exits-one`
  runs all four commands with no argument and compares the whole result map.
  `bb test` green (168 tests, 488 assertions).

### INFO-1 — Repeat tests can't see whether `:unchanged` writes
- **Axis:** correctness
- **Location:** `src/gaoshun/controllers/task.clj` (`transition!`)
- **Problem:** on `:unchanged` the logic returns the stored task as read, so
  removing the `:applied` guard would write identical values and the repeat
  tests would still pass. The user-facing property (the original
  `finish_date` survives) is pinned at both the logic and CLI levels.
- **Fix:** none needed for behavior. A `with-redefs` check on `update!` would
  pin the guard, but diplomat-architecture rules out controller tests.
- **Verified:** light check, read `transition!` and `transition`.
- **Status:** open.

### INFO-2 — Four dispatch entries repeat the same shape
- **Axis:** alternatives
- **Location:** `src/gaoshun/diplomat/cli.clj` (`dispatch`)
- **Problem:** `start`, `done`, `drop` and `reopen` differ only in the command
  name.
- **Fix:** optional: generate them from `transition-messages`. The reviewer
  would not insist; the explicit form matches `add` and `list`.
- **Status:** open.

### INFO-3 — Acceptance criteria unchecked
- **Axis:** spec-adherence
- **Location:** `.agents/tasks/gaoshun-task-cli/5_task.md`
- **Fix:** tick them once the suite is green.
- **Status:** resolved; all six ticked after the fix round.

## Requirements ledger

| Requirement (spec section) | Status | Evidence |
|---|---|---|
| `transition [task command today]` returns `:applied`, `:unchanged` or the illegal-transition error | met | `logic/task.clj` `transition` |
| `start`: applied from todo, unchanged on doing, illegal from closed | met | `transitions` table; unit test |
| `done`: applied from open with finish = today, unchanged on done, illegal from dropped | met | unit test |
| `drop`: applied from open with finish = today, unchanged on dropped, illegal from done | met | unit test |
| `reopen`: applied from closed, status todo, finish nil, unchanged on open | met | unit test |
| New task built through `make-task` | met | `transition` |
| No write on `:unchanged`; `finish_date` survives a repeat | met | `controllers/task.clj`; repeat tests (see INFO-1) |
| `transition!` returns `:task/not-found`, uses the clock's date | met | `controllers/task.clj` |
| `update!` writes every mutable column by id in one statement | met | `diplomat/sqlite/task.clj`; three round-trip tests |
| Eight success and no-op messages | met | `transition-messages`; integration tests |
| `:task/illegal-transition` message | met | `error-message`; illegal-move test |
| Bad id and unknown id | met | integration tests |
| No ID prints usage and exits 1 | partial | implemented via `fallback`; no test (WARNING-1) |
| Done parent with an open subtask shows as a dimmed header | met | integration test |
| 16 unit cells; end-to-end applied, no-op, illegal, unknown per spec | met | logic and integration tests |
| Non-goal: no direct done↔dropped move | met | illegal-move test |

## Discarded

None.

## Verified clean

The transition table is data with one `cond`; `update!` reuses
`task->params` and binds the id last to match `WHERE id = ?`; the controller
writes only on `:applied`; the CLI diplomat requires no sqlite namespace; and
the illegal and repeat integration tests assert the stored row, so a write
that changed status or `finish_date` would fail them.

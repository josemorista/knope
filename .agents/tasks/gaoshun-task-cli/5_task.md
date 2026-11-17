---
name: status-transitions
overview: "Add `start`, `done`, `drop` and `reopen` following the idempotent transition table, with `finish_date` handling and the dimmed context-parent header verified end to end."
isProject: false
---

# Task 5.0: Status transitions

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** every cell of the transition table behaves as specified through
  the CLI: applied moves write and print their message, repeats print
  `already ...` and exit 0 without writing, illegal moves exit 1.
- **Scope:** `logic.task/transition`, SQLite `update!`,
  `controllers.task/transition!`, four CLI commands, their messages.
- **Why this task:** completes ship group B, and 6.0, 7.0 and 8.0 all need
  finished tasks or `update!` in their tests.
- **Ship group:** B, with 4.0.
- **State at the boundary:** suite green; the tool supports the full task
  lifecycle.
- **Dependencies:** 4.0.
- **Review focus:** the `:unchanged` path must not write, so an existing
  `finish_date` survives a repeated `done`.
- **Non-goals:** moving directly between `done` and `dropped` (illegal by
  design; use `reopen`).

<skills>
## Standard skills compliance

- **`test-driven-development`:** one logic test per table cell before
  implementing `transition`.
- **`diplomat-architecture`:** the controller branches on `:outcome`; only
  `:applied` calls `update!`.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Transition table | § Shared contracts, "Transitions" | Implement exactly; 4 commands × 4 statuses = 16 cells. |
| Messages | § Shared contracts, "Transitions" | Four applied messages, four no-op messages. |
| Errors | § Shared contracts, "Error values" | Add `:task/illegal-transition`; `:task/not-found` and `:validation/bad-id` reused. |
| Concurrency | § Failure and operational behavior | Read-then-write is not atomic; accepted. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [x] **5.1 `logic.task/transition`**
  - Change: `[task command today]` returns `{:outcome :applied :task t}`,
    `{:outcome :unchanged :task t}`, or the illegal-transition error map with
    the id and current status in details. Build the new task through
    `make-task`, so the `finish_date` invariant is enforced.
  - Verify: all 16 cells, whole-map comparison.
- [x] **5.2 SQLite `update!`**
  - Change: update every mutable column of a task by id, one statement.
  - Verify: update then `find-by-id` returns the updated map.
- [x] **5.3 `controllers.task/transition!`**
  - Change: `[id command components]`: find, return `:task/not-found` if
    missing, transition with today from the clock, `update!` only on
    `:applied`.
  - Verify: covered by integration tests.
- [x] **5.4 CLI commands**
  - Change: dispatch entries for `start`, `done`, `drop`, `reopen`, each
    taking `ID` through `parse-id`; message mapping by command and outcome.
  - Verify: integration tests below.
- [x] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [x] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [x] `done N` sets status and `finish_date` to the clock's date; a second
  `done N` with a later clock prints `Task N is already done.` and leaves the
  original date.
- [x] `reopen N` on a done task sets `todo` and nulls `finish_date`.
- [x] `drop N` on a done task exits 1 with `Task N is done; reopen it first.`
- [x] Marking a parent done while a subtask is open makes `list` show the
  parent as a dimmed header with the subtask under it.
- [x] Unknown id exits 1 with `Task N not found.`
- [x] The tree is green at the boundary.

## Task tests

- **Unit:** 16 transition cells.
- **Integration / contract:** `update!` round trip.
- **End-to-end:** one applied and one no-op case per command, one illegal
  case, unknown id, and the context-parent `list` output.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/logic/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

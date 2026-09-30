---
name: add-end-to-end
overview: "Build `add` through every layer: task constructor, date parsing, adapters, insert, controller, and the CLI `run`/`-main` entry point with its first error messages."
isProject: false
---

# Task 2.0: `add` end to end

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `run` with `["add" "Write report" "--due" "tomorrow"]` inserts a
  task and returns `{:exit 0 :out "Added task 1.\n" :err ""}`; invalid input
  returns exit 1 with the spec's message.
- **Scope:** `make-task`, `new-task`, `parse-date-input`, argv and row
  adapters, `insert!`, `find-by-id`, `add!`, and the CLI dispatch skeleton.
- **Why this task:** it establishes the shape every later command copies: argv
  adapted in the diplomat, a controller returning a task or an error map, and
  the message table.
- **Ship group:** A, with 1.0 and 3.0.
- **State at the boundary:** suite green. `add` works through `bb -m
  gaoshun.diplomat.cli`; there is no way to see tasks until 3.0.
- **Dependencies:** 1.0.
- **Review focus:** the `make-task` invariants and the fixed `created_at`
  formatter, which the `day` query depends on later.
- **Non-goals:** `--parent` (4.0), listing (3.0), `--note` beyond storing it.

<skills>
## Standard skills compliance

- **`test-driven-development`:** Red-Green-Refactor per todo; the integration
  test is the Red step for `add!`.
- **`diplomat-architecture`:** `diplomat.cli` requires adapters and controllers
  only; `controllers.task` never requires `adapters.task`. Generator before
  tests.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Task map | § Shared contracts, "Task map" | Exact key set; `ex-info` with `{:type :gaoshun/invariant}` on violation. |
| Date input | § Shared contracts, "Date input" | All six forms; nil for garbage, adapter maps nil to `:validation/bad-date`. |
| Storage formats | § Data and storage, "Storage formats" | `created_at` through one `yyyy-MM-dd'T'HH:mm:ss` formatter. |
| Errors | § Shared contracts, "Error values" | Add `:validation/missing-title` and `:validation/bad-date` to the table now. |
| Output | § Shared contracts, "Other command output" | `Added task N.` |
| CLI | § Component changes (`diplomat/cli.clj`) | Positional args and value options stay strings. `run` returns `{:exit :out :err}`. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **2.1 Generator and `make-task`**
  - Change: `logic/task.clj` with `make-task` and `new-task [attrs now]`;
    `test/gaoshun/gen/task.clj` builds valid tasks with overrides through
    `make-task`, keeping status and `:finish-date` consistent.
  - Verify: each invariant (unknown status, blank title, finish date present on
    an open task or missing on a closed one) throws.
- [ ] **2.2 `logic.date/parse-date-input`**
  - Change: ISO, `today`, `tomorrow`, `yesterday`, `+Nd`, `-Nd`; nil otherwise.
  - Verify: every form, `+0d`, month and year rollover, garbage.
- [ ] **2.3 Adapters**
  - Change: `adapters/task.clj` with `args->new-attrs [opts today]`,
    `row->task` (snake_case keywords to the task map, via `make-task`) and
    `task->params`. Empty `--note` becomes nil.
  - Verify: row round trip, including `created_at` at zero seconds; missing
    title and bad date return error maps.
- [ ] **2.4 SQLite `insert!` and `find-by-id`**
  - Change: `diplomat/sqlite/task.clj`; `insert!` returns
    `:last-inserted-id`; `find-by-id` returns a task or nil. `?` placeholders.
  - Verify: insert then find returns an equal task map.
- [ ] **2.5 Controller and CLI**
  - Change: `controllers.task/add!` reads the clock once and inserts.
    `diplomat/cli.clj` with a `babashka.cli` dispatch table, `run
    [components opts argv]`, `-main`, usage on `--help` (exit 0) and unknown
    commands (exit 1), and the message table for the two codes above.
  - Verify: integration tests below.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] `add "x"` prints `Added task N.` and the row exists with status `todo`.
- [ ] `add "x" --due -2d` stores the date two days before the fixed clock's
  date. If `babashka.cli` claims `-2d` as a flag, handle it as the techspec's
  "Leading-dash values" risk describes.
- [ ] `add ""` and `add x --due fri` exit 1 with the table's messages on stderr.
- [ ] `--help` exits 0; an unknown command exits 1; both print usage.
- [ ] The tree is green at the boundary.

## Task tests

- **Unit:** `make-task` invariants; `parse-date-input` forms; adapter round
  trip and validation errors.
- **Integration / contract:** `insert!`/`find-by-id` on a temp DB.
- **End-to-end:** `test/gaoshun/integration/cli_test.clj`: `add` happy path,
  `--due -2d`, missing title, bad date, `--help`, unknown command.

## Open questions

None.

## Relevant files

- **Create:** `src/gaoshun/logic/task.clj`, `src/gaoshun/logic/date.clj`
- **Create:** `src/gaoshun/adapters/task.clj`
- **Create:** `src/gaoshun/diplomat/sqlite/task.clj`
- **Create:** `src/gaoshun/controllers/task.clj`
- **Create:** `src/gaoshun/diplomat/cli.clj`
- **Create:** `test/gaoshun/gen/task.clj` and one test namespace per source namespace above except the controller
- **Create:** `test/gaoshun/integration/cli_test.clj`

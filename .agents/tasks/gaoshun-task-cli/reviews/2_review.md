---
feature: gaoshun-task-cli
reviewed: task 2.0, working tree vs HEAD (42dae3d) on feat/add-and-list
diff-command: git diff HEAD -- src test && git ls-files --others --exclude-standard -- src test
spec: .agents/tasks/gaoshun-task-cli/techspec.md
verdict: changes-requested
findings: { blocker: 0, warning: 1, info: 6 }
axes: [spec, correctness, standards, alternatives]
axes-skipped: []
panel: [spec: sonnet, correctness: opus, standards: haiku, alternatives: sonnet]
---

# Review: task 2.0, `add` end to end

## Verdict

`add` works as specified and every requirement is met. One Warning stays open
for the developer: a value option given with no value (`add x --due`) prints a
stack trace, and fixing it needs a user-facing message the spec doesn't define.

## Scope

- **Reviewed:** `src/gaoshun/logic/task.clj`, `src/gaoshun/logic/date.clj`,
  `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`,
  `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`, and the
  six new test namespaces under `test/gaoshun/`.
- **Set aside:** `.clj-kondo/`, `.lsp/` (editor caches); `.agents/**` (spec
  artifacts: 2_task.md checkboxes and a "Result (increment 2)" note under the
  techspec's "Leading-dash values" risk).

## Coverage

- **Axes run:**
  - spec: walked task 2.0 and the techspec both ways; no missing or divergent
    requirement, no unauthorized files.
  - correctness: traced `add` from argv to row, the `created_at` round trip,
    invariants, and option edge cases.
  - standards: diplomat-architecture layer map for every require, TDD, generator
    before tests, whole-map assertions.
  - alternatives: proportion, reuse of `babashka.cli` and `java.time`.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the spec reviewer did not read task 1.0's
  files (already committed). The correctness reviewer did not check how the pod
  binds a boolean; its scenario for that was checked here instead (see
  Discarded).
- **Not covered by this gate:** the test suite. CI runs it.

## Findings

### WARNING-1 — A value option with no value prints a stack trace
- **Axis:** correctness
- **Location:** `src/gaoshun/diplomat/cli.clj:36-43`
- **Problem:** `gaoshun add x --due` or `add x --note` makes `babashka.cli`
  throw `ExceptionInfo: Missing value for option --due`. `run` catches nothing,
  so the user sees a stack trace for a typo. Techspec § Failure and operational
  behavior says anything thrown is a bug.
- **Evidence:** ran `GAOSHUN_DB=<tmp> bb --config bb.edn -m gaoshun.diplomat.cli add x --note`
  and `... add x --due`: both print the error block and exit 1; no row is
  written.
- **Fix:** give the dispatch an `:error-fn` that returns an error value, with a
  new code and message in the message table (for example
  `:validation/missing-value` → `--due needs a value.`), plus integration tests
  for both options. Adding a code changes the techspec's error table, so the
  wording is the developer's call.
- **Verified:** reproduced through `-main` as above. The reviewer's original
  claim (bogus note stored, exit 0) was wrong; see Discarded.
- **Status:** open (needs a decision on the message; not sent to the fix round).

### INFO-1 — `parse-date-input` throws on very large offsets
- **Axis:** correctness
- **Location:** `src/gaoshun/logic/date.clj:19-20`
- **Problem:** `+99999999999999999999d` throws `NumberFormatException`;
  `+999999999999d` throws `DateTimeException` from `plusDays`. The contract
  (§ Date input) is "returns a `LocalDate` or nil".
- **Fix:** catch both inside `parse-date-input` and return nil; add both inputs
  to the garbage test.
- **Verified:** light check; the implementer reported the same behavior.
- **Status:** open.

### INFO-2 — `add` reads the clock twice
- **Axis:** correctness
- **Location:** `src/gaoshun/diplomat/cli.clj:24`, `src/gaoshun/controllers/task.clj:7`
- **Problem:** the CLI reads the clock for `today` and `add!` reads it again for
  `created-at`; across midnight `--due today` and `created_at` land on
  different days. The implementer flagged this as a consequence of adapting
  argv before the controller.
- **Fix:** read the clock once in the CLI and pass `now` to both, or accept it.
- **Verified:** light check; read both lines.
- **Status:** open.

### INFO-3 — `add --help` reports a missing title
- **Axis:** correctness
- **Location:** `src/gaoshun/diplomat/cli.clj:23-29`
- **Problem:** `{:help true}` reaches the `add` handler, which returns
  `A task needs a title.` with exit 1. The spec only defines top-level
  `--help`.
- **Fix:** check `:help` in `add` first, or leave subcommand help out of scope.
- **Verified:** light check; read the handler.
- **Status:** open.

### INFO-4 — Controller requires `diplomat.sqlite.task`, which the literal layer-map pattern doesn't name
- **Axis:** standards
- **Location:** `src/gaoshun/controllers/task.clj:2`
- **Problem:** the diplomat-architecture layer map lets controllers access
  "diplomat-datomic" (pattern `.*\.(db|diplomat\.datomic)\..*`);
  `gaoshun.diplomat.sqlite.task` doesn't match that pattern.
- **Evidence:** diplomat-architecture "Layer Map & Access Rules", controller
  row.
- **Fix:** none recommended. The SQLite diplomat is this project's database
  diplomat, the role diplomat-datomic plays in the map.
- **Verified:** read the require and the techspec. Kept at Info because the
  rule's intent (controllers call the database diplomat) holds.
- **Reopens a settled decision:** techspec § System shape names
  `diplomat.sqlite.task` and says controllers call the SQLite diplomat.
- **Status:** open.

### INFO-5 — Test extras outside the task's listed tests
- **Axis:** scope-creep
- **Location:** `test/gaoshun/diplomat/sqlite/task_test.clj:42-45`, `test/gaoshun/adapters/task_test.clj:9-12`
- **Problem:** an FTS-on-insert test (increment 6's area, harmless here) and a
  `without-parent` helper that strips a key `args->new-attrs` never returns.
- **Fix:** optional; drop `without-parent` until 4.0 needs it.
- **Verified:** light check.
- **Status:** open.

### INFO-6 — Acceptance criteria boxes unchecked
- **Axis:** spec-adherence
- **Location:** `.agents/tasks/gaoshun-task-cli/2_task.md:86-92`
- **Fix:** tick them.
- **Status:** resolved (ticked when the batch run recorded the task).

## Requirements ledger

| Requirement (spec section) | Status | Evidence |
|---|---|---|
| Task map exact 8 keys; `ex-info` `:gaoshun/invariant` (§Shared contracts) | met | `logic/task.clj:11,21-28`; `logic/task_test.clj:27-64` |
| Invariants: unknown status, blank title, finish-date iff closed (2.1) | met | `logic/task.clj:15-20` |
| `created-at` truncated to seconds (§Task map) | met | `logic/task.clj:26` |
| `new-task [attrs now]`: `:todo`, `created-at` = now | met | `logic/task.clj:30-39` |
| Generator builds valid tasks via `make-task` with overrides (2.1) | met | `test/gaoshun/gen/task.clj:11-29` |
| `parse-date-input`: six forms, `+0d`, rollover, garbage → nil (2.2) | met | `logic/date.clj:11-21`; see INFO-1 for huge offsets |
| Adapter maps nil date to `:validation/bad-date` | met | `adapters/task.clj:23-24` |
| `args->new-attrs`: missing title error; empty note → nil (2.3) | met | `adapters/task.clj:16-29` |
| `row->task`/`task->params` round trip incl. zero seconds (2.3) | met | `adapters/task.clj:31-52` |
| Fixed `created_at` formatter (§Storage formats) | met | `adapters/task.clj:8,50` |
| `insert!` returns `:last-inserted-id`; `find-by-id` task or nil; `?` placeholders (2.4) | met | `diplomat/sqlite/task.clj:5-17` |
| `add!` reads clock, inserts, returns task with id (2.5) | met | `controllers/task.clj:5-9`; see INFO-2 |
| `run [components opts argv]` returns `{:exit :out :err}` | met | `diplomat/cli.clj:36-43` |
| `Added task N.` (§Other command output) | met | `cli.clj:29` |
| Message table: missing-title, bad-date, verbatim | met | `cli.clj:14-18` |
| Errors to stderr, exit 1 | met | `cli.clj:20-21` |
| `--help` exit 0; unknown command exit 1; usage | met | `cli.clj:31-34` |
| `-main`: components, `:color?` from `GAOSHUN_TTY`/`NO_COLOR`, exit code | met | `cli.clj:45-56` |
| Value options and positionals stay strings | met | `cli.clj:41` |
| `--due -2d` stores D-2 (AC 2) | met | `cli_test.clj:51-53`; result recorded in techspec |
| Layering: no sqlite in `diplomat.cli`, no adapters in controller | met | `cli.clj:2-5`; `controllers/task.clj:2-3` |
| Task tests: unit, sqlite on temp DB, integration | met | six test namespaces |
| No controller unit test | met | none exists |
| Acceptance criteria boxes | met after record | INFO-6 |

## Discarded

| Axis | Finding | Why it was dropped |
|---|---|---|
| correctness | bare `--note` stores a boolean note and exits 0; bare `--due` reports "Can't read date 'true'" | ran both through `-main`: `babashka.cli` throws "Missing value for option" before the adapter runs, exit 1, no row written. The real defect is kept as WARNING-1. |

## Verified clean

The `created_at` formatter keeps zero seconds on write and read, and the
insert/find round trip is exact across note, due date and closed statuses. The
layer rules the techspec names hold, and `babashka.cli` and `java.time` do the
parsing without hand-rolled replacements.

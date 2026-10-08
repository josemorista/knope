# Technical specification: gaoshun task CLI

Design: `.agents/tasks/gaoshun-task-cli/design.md` (reviewed in `design-review.md`).
This spec covers how to build it. The design stays the reference for product
behavior; where this spec refines a design rule, it says so.

## Reviewer guide

- **Outcome:** a babashka CLI, `gaoshun`, backed by one SQLite file with an FTS5
  index, built in Diplomat layers with tests for logic, adapters, the SQLite
  diplomat, and the CLI end to end.
- **Scope:** all commands in the design (`add`, `start`, `done`, `drop`,
  `reopen`, `edit`, `rm`, `list`, `day`, `search`), schema creation, rendering,
  and the `bin/gaoshun` wrapper.
- **Key decisions:** the pod takes a file path per call, so the SQLite component
  is a path and nothing spans two calls; the clock is a component; transitions
  are idempotent; every error is a map carrying `:error/code`; `list` loads
  every task and builds the tree in logic; delivery is in vertical slices.
- **Review focus:** the `day` predicate in "Data and storage", the fixed
  `created_at` format that the `day` string comparison depends on, the FTS
  trigger set, and the `search-match` filtering.
- **Blockers / open questions:** none. The runtime facts listed under "Risks"
  are checked in increment 1 before any feature code.
- **Non-goals:** backdating `created_at`, tags, JSON output, a migration
  framework, arbitrary nesting, multi-process locking beyond SQLite's own.

## Architecture and contracts

### System shape

Namespaces live under `src/gaoshun/`, with dependencies pointing downward only:

- **`diplomat.cli`:** entry point. `-main` builds real components, calls `run`,
  prints, and exits. `run` takes components, options, and argv, and returns
  `{:exit n :out str :err str}` without printing, which lets the integration
  test drive the whole CLI in-process.
- **`diplomat.cli.render`:** turns display rows into lines. Glyphs,
  indentation, ANSI color. No task logic.
- **`adapters.task`:** the only translation layer. Converts argv strings to task
  attributes, SQLite rows to tasks, tasks to SQL parameters, tasks to display
  rows, and search text to an FTS match expression.
- **`controllers.task`:** one function per command. Reads the clock once,
  calls logic, calls the SQLite diplomat, returns tasks or error maps.
- **`logic.task`, `logic.date`:** pure functions. No clock, no I/O.
- **`diplomat.sqlite.db`:** pod require, schema creation, `user_version`.
- **`diplomat.sqlite.task`:** every SQL statement for tasks. Returns tasks (via
  `adapters.task/row->task`), never raw rows.
- **`components`:** builds `{:sqlite db-path :clock now-fn}` and runs
  `ensure-schema!` on the database before returning it.

The layer rules are the design's ("Architecture"), and `diplomat-architecture`
is the review checklist for them. Two are worth stating because the first draft
of this spec broke the second. Controllers do not require `adapters.task`: the
CLI diplomat adapts argv before calling a controller and adapts results after.
And `diplomat.cli` does not require any `diplomat.sqlite.*` namespace: an
entry-point diplomat may use adapters and controllers only, so schema setup
happens in `components`, the composition root, which is allowed to know every
diplomat.

### Shared contracts

**Task map.** Built only by `logic.task/make-task`, which returns a map with
exactly these keys and throws `ex-info` with `{:type :gaoshun/invariant}` when
one of its checks fails:

- `:id` long, or nil before insert
- `:parent-id` long or nil
- `:title` non-blank string
- `:note` string or nil (an empty string is stored as nil)
- `:status` one of `:todo :doing :done :dropped`
- `:created-at` `java.time.LocalDateTime`, truncated to seconds
- `:due-date` `java.time.LocalDate` or nil
- `:finish-date` `LocalDate`, present exactly when status is `:done` or `:dropped`

This refines the design. `make-task` checks only what it can see in the map
itself. "A subtask cannot be a parent" needs the parent row, so it is checked
by `logic.task/parent-error` in the controller and returned as an error value,
not thrown.

**Components.** `{:sqlite "/abs/path/tasks.db" :clock (fn [] (LocalDateTime/now))}`.
The path comes from `GAOSHUN_DB`, falling back to `$HOME/.gaoshun/tasks.db`.
Tests pass a temp path and `(constantly fixed-now)`.

**Error values.** Every expected failure is `{:error/code kw :error/details {...}}`.
This also refines the design, which allowed bare keywords: messages need the id
and the status, and a bare keyword cannot carry them. `diplomat.cli` owns this
message table and is the only place codes become text:

| Code | Message |
|---|---|
| `:task/not-found` | `Task 12 not found.` |
| `:task/nesting-too-deep` | `Task 3 is a subtask; subtasks can't have subtasks.` |
| `:task/has-subtasks` | `Task 3 has 2 subtasks. Use --force to delete them too.` |
| `:task/illegal-transition` | `Task 12 is done; reopen it first.` |
| `:validation/bad-id` | `'abc' is not a task id.` |
| `:validation/bad-date` | `Can't read date 'fri'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.` |
| `:validation/missing-title` | `A task needs a title.` |
| `:validation/nothing-to-edit` | `Nothing to change. Pass --title, --due, --note or --clear-due.` |
| `:validation/conflicting-flags` | `--due and --clear-due can't be used together.` |
| `:validation/empty-search` | `Search needs at least one word.` |
| `:validation/bad-page` | `--page needs a number.` |

All of these print to stderr and exit 1. An unknown command prints usage and
exits 1; `--help` prints usage and exits 0.

**Transitions.** `logic.task/transition` takes a task, a command keyword, and
`today`, and returns `{:outcome :applied :task t}`, `{:outcome :unchanged :task t}`,
or an `:task/illegal-transition` error. The table is the one agreed in
planning, restated here because it is the contract:

| Command | Applied from | Unchanged when | Illegal from | Effect |
|---|---|---|---|---|
| `start` | `todo` | `doing` | `done`, `dropped` | status `doing` |
| `done` | `todo`, `doing` | `done` | `dropped` | status `done`, finish = today |
| `drop` | `todo`, `doing` | `dropped` | `done` | status `dropped`, finish = today |
| `reopen` | `done`, `dropped` | `todo`, `doing` | none | status `todo`, finish = nil |

On `:unchanged` the controller does not write, so an existing `finish_date` is
kept. Success messages: `Task 12 started.`, `Task 12 marked done.`,
`Task 12 dropped.`, `Task 12 reopened.`, and for no-ops `Task 12 is already
in progress.`, `... already done.`, `... already dropped.`, `... already open.`

**Other command output.** `add` prints `Added task 12.` (the design's "prints
the new id"). `edit` prints `Updated task 12.` `rm` prints `Deleted task 12.`,
or `Deleted task 12 and 2 subtasks.` for a forced delete. `day` prints the date
on its own line, then each non-empty section as a heading (`Done`, `Dropped`,
`Open`) followed by its task lines indented two spaces. `search` prints one flat
task line per match, in relevance order.

**Date input.** `logic.date/parse-date-input [s today]` accepts `YYYY-MM-DD`,
`today`, `tomorrow`, `yesterday`, `+Nd` and `-Nd`, and returns a `LocalDate`
or nil. The adapter turns nil into `:validation/bad-date`. Past due dates are
allowed.

**Relative due label.** `logic.date/due-label [due today]` returns
`{:text s :urgency u}`: `today`/`tomorrow` are `:soon`; `in Nd` for 2 to 7 days
is `:later`; beyond 7 days the ISO date is shown, also `:later`; any past date
is `Nd overdue` and `:overdue`. The renderer maps urgency to red, yellow, and dim.

### Data and storage

**Storage formats.** Dates are `YYYY-MM-DD`. `created_at` is always written as
`yyyy-MM-dd'T'HH:mm:ss` through one formatter in `adapters.task`, never with
`.toString`. `LocalDateTime.toString` drops zero seconds and prints a variable
number of fraction digits, and the `day` query compares these values as strings.

**Schema.** `diplomat.sqlite.db/ensure-schema!` creates the parent directory,
reads `PRAGMA user_version`, and when it is 0 runs each statement below as a
separate `execute!` call and then sets `user_version = 1`. The statements keep
`IF NOT EXISTS`, so a run interrupted halfway through is repaired by the next
one. The `tasks` table and its four indexes are exactly as in the design. The
FTS part is shown because the external-content trigger pattern is easy to get
subtly wrong:

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts
  USING fts5(title, note, content='tasks', content_rowid='id');

CREATE TRIGGER IF NOT EXISTS tasks_fts_ai AFTER INSERT ON tasks BEGIN
  INSERT INTO tasks_fts(rowid, title, note) VALUES (new.id, new.title, new.note);
END;
CREATE TRIGGER IF NOT EXISTS tasks_fts_ad AFTER DELETE ON tasks BEGIN
  INSERT INTO tasks_fts(tasks_fts, rowid, title, note)
    VALUES ('delete', old.id, old.title, old.note);
END;
CREATE TRIGGER IF NOT EXISTS tasks_fts_au AFTER UPDATE OF title, note ON tasks BEGIN
  INSERT INTO tasks_fts(tasks_fts, rowid, title, note)
    VALUES ('delete', old.id, old.title, old.note);
  INSERT INTO tasks_fts(rowid, title, note) VALUES (new.id, new.title, new.note);
END;
```

The update trigger is limited to `title` and `note`, so status changes never
touch the index. The pod takes a path on every call and probably opens a
connection each time, so there are no transactions across calls and
`PRAGMA foreign_keys` is effectively off. Nothing relies on either: every write
is a single statement, and triggers run inside the statement that fires them.

**Queries.** All statements use `?` placeholders with positional arguments,
the only binding form the pod's README shows. The two reads that are easy to
get wrong:

```sql
-- day: all three sections in one call, split in logic.
-- args: [d next-day d]
SELECT * FROM tasks
WHERE finish_date = ?
   OR (created_at < ? AND (finish_date IS NULL OR finish_date > ?));

-- search. args: [match-expression]
SELECT t.* FROM tasks_fts f JOIN tasks t ON t.id = f.rowid
WHERE tasks_fts MATCH ? ORDER BY bm25(tasks_fts);
```

In the `day` query, `next-day` is D plus one, formatted as a date. With indexes
on both `finish_date` and `created_at`, each side of the `OR` is indexable,
which lets SQLite use both instead of scanning; the day slice confirms this
with `EXPLAIN QUERY PLAN`.

`list` and `list --all` read one page of 50 at a time. `list` runs
`list-open` (`WHERE status IN ('todo', 'doing')`, served by `tasks_status_idx`)
and `list --all` runs `list-all`. Both use one shared `ORDER BY` that mirrors
`logic.task/build-tree`'s sort, so `LIMIT ? OFFSET ?` pages through the order
the user sees; a sqlite test checks the two orders agree. The controller asks
for 51 rows: the 51st is dropped and only tells `run` to print
`More on page N: gaoshun list [--all] --page N`. `--page` is 0-based, defaults
to 0, and anything other than a whole number from 0 is `:validation/bad-page`.
A page past the end prints `Nothing pending.` At 20,000 rows with 200 open,
loading the whole table took about 500 ms; `list` now takes about 3 ms and
`list --all` 15 to 51 ms depending on the page.

From 4.0, a page counts root families, not rows, so a parent is never split
from its children: the query pages the roots (open roots, plus closed roots
with an open child) and then fetches every child of those roots, which also
keeps progress counts complete.

The search argument comes from `adapters.task/search-match`. It splits the
input on whitespace, drops every word that contains no letter or digit, doubles
any `"` inside the remaining words, and joins `"word"*` terms with spaces. When
no word remains it returns `:validation/empty-search`, so a punctuation-only
query never reaches FTS5.

## Implementation design

### Component changes

All files are new.

- **`bb.edn`:** `:paths ["src"]`, `:pods {org.babashka/go-sqlite3 {:version "0.3.9"}}`,
  and a `test` task that adds `test/` to the path, loads every test namespace,
  and exits non-zero on any failure or error.
- **`src/gaoshun/components.clj`:** `(components env)` resolves the database
  path, calls `diplomat.sqlite.db/ensure-schema!` on it, and returns the
  components map. The only place that reads `GAOSHUN_DB` and `HOME`. The test
  fixture calls the same function with a temp path, so tests get schema setup
  the same way the CLI does.
- **`src/gaoshun/logic/date.clj`:** `parse-date-input`, `due-label`, and
  `next-day`, as described under "Shared contracts".
- **`src/gaoshun/logic/task.clj`:** `make-task`, `new-task [attrs now]` (status
  `:todo`, `created-at` = now), `transition`, `parent-error [parent-id parent]`
  (not found, or the parent is itself a subtask), `apply-edit [task changes]`,
  `progress [children]` (`{:done n :planned m}`, or nil when `m` is 0),
  `build-tree [tasks {:all? bool}]`, and `day-sections [tasks day]`.
  - `build-tree` groups children under roots. Without `:all?`, it keeps a root
    that is open or has an open child, marks a closed root as `:context? true`,
    and shows only open children. Progress always counts every child. Sorting:
    open before closed; open by due date ascending with nil last, then id; closed
    by finish date descending, then id descending. The same rules order children.
  - `day-sections` returns `{:done [...] :dropped [...] :open [...]}` using the
    design's three predicates.
- **`src/gaoshun/adapters/task.clj`:** `row->task`, `task->params`,
  `args->new-attrs [opts today]`, `args->changes [opts today]`, `parse-id`,
  `parse-page`,
  `search-match`, and `task->display-row [task today extras]`. Display rows
  carry the glyph (`[ ]`, `[~]`, `[x]`, `[-]`), id, title, due label and
  urgency, `3/5` progress text, `↳ #12` parent reference (used by `day` and
  `search`), and a `:dimmed?` flag.
- **`src/gaoshun/controllers/task.clj`:** `add!`, `transition!`, `edit!`,
  `remove!`, `list-tasks`, `day`, and `search`, each ending with components as
  its last argument. `list-tasks [all? page components]` returns
  `{:tree :more?}`. `add!` with a parent checks `parent-error` first.
  `remove!` returns `:task/has-subtasks` unless forced, and a forced delete of
  a parent is the single statement `DELETE FROM tasks WHERE id = ? OR parent_id = ?`.
- **`src/gaoshun/diplomat/sqlite/db.clj`:** `ensure-schema!`. The only namespace
  besides `diplomat.sqlite.task` that requires the pod namespace.
- **`src/gaoshun/diplomat/sqlite/task.clj`:** `find-by-id`, `count-children`,
  `insert!` (returns the id from `:last-inserted-id`), `update!`, `delete!`,
  `list-open`, `list-all`, `day-rows`, and `search`.
- **`src/gaoshun/diplomat/cli.clj`:** a `babashka.cli` dispatch table. The
  three boolean flags, `--force`, `--all` and `--clear-due`, are coerced to
  `:boolean` so that `rm --force 3` does not read `3` as the flag's value.
  Every value-bearing option and positional argument stays a string, so the
  adapter produces our error messages rather than the library's. `run` adapts,
  calls the controller, and turns the result into output or an error message;
  it requires no `diplomat.sqlite.*` namespace. `-main` builds components,
  computes `{:color? ...}`, and exits with the code `run` returns.
- **`src/gaoshun/diplomat/cli/render.clj`:** `tree-lines`, `section-lines`, and
  `flat-lines`, which turn display rows into strings, color only when `:color?`
  is set. Subtasks are indented two spaces. Empty states: `Nothing pending.`,
  `Nothing recorded for 2026-09-28.`, `No matches.`
- **`bin/gaoshun`:** a bash wrapper. It resolves its own symlink to find the
  repo, always sets `GAOSHUN_TTY` to `1` when `[ -t 1 ]` and to `0` otherwise
  (so a value exported in the user's shell never leaks through), and runs
  `exec bb --config "$root/bb.edn" -m gaoshun.diplomat.cli "$@"`.

**Color detection.** `-main` enables color when `GAOSHUN_TTY=1` and `NO_COLOR`
is unset. The shell test is the reliable way to detect a terminal:
`System/console` no longer means "is a terminal" on newer JDKs (it returns a
Console even when output is redirected), so the JVM can't answer the question
reliably.

### Failure and operational behavior

- **Idempotency:** covered by the transition table. `rm` of a missing id is
  `:task/not-found`, not a no-op, because deletion by mistyped id should be
  loud.
- **Concurrency:** single user. SQLite only makes a second writer wait if a
  busy timeout is set, and a per-call connection would not keep a `PRAGMA`.
  Increment 1 runs two overlapping writes and records whether the second waits
  or fails with "database is locked" (see "Risks"). The read-then-write in
  transitions is not atomic, and that is accepted.
- **Unexpected errors:** `run` catches nothing. Expected failures are already
  error maps by the time they reach it, so anything thrown, including a
  `:gaoshun/invariant` from `make-task`, is a bug. It propagates to `-main`,
  which lets the stack trace print and exits 1.
- **First run:** `components` runs `ensure-schema!`, which creates `~/.gaoshun/`
  and the schema. The
  first run also downloads the pod into `~/.babashka/pods`, which needs network
  access once.
- **Observability, rollout, rollback:** not applicable to a local tool. The
  database file is the only state, and deleting it resets the tool.

## Testing and delivery

### Test behaviors

The generator `test/gaoshun/gen/task.clj` is written first and builds valid
tasks through `make-task`, with overrides. A shared fixture in
`test/gaoshun/helpers.clj` builds components through `components` with a temp
path, then swaps in a fixed clock. Assertions compare whole maps with `=`. The
behaviors below are grouped by namespace; the increments say which slice writes
which of them.

- **`logic.date`:** every input form, including `+0d`, month and year rollover,
  garbage input, and each `due-label` boundary (-1, 0, 1, 2, 7, 8 days).
- **`logic.task`:** every cell of the transition table; each `make-task`
  invariant throws; `parent-error` for missing and nested parents; `progress`
  excludes dropped children and returns nil when all are dropped; `build-tree`
  sorting, closed-parent context, and hidden closed children; `day-sections`
  for a task finished on D, dropped on D, open across D, created after D, and
  finished before D.
- **`adapters.task`:** row round trip, including the fixed `created_at` format
  at zero seconds; every validation error; `search-match` quoting of `"`,
  dropping of words with no letter or digit (`-`, `.`, `"`), and
  `:validation/empty-search` when nothing remains.
- **`diplomat.sqlite.task`:** against a real temp DB: insert and find; FTS
  finds a task after insert, finds the new title after an update, and stops
  finding it after delete; status updates leave the index unchanged; a search
  built by `search-match` from words mixing punctuation and letters (`auth.svc`)
  runs without an FTS error; the `day` query at the midnight boundary; the
  cascade delete.
- **`integration.cli`:** one test per command through `run`, asserting `:exit`,
  `:out` and `:err`, and for writes the resulting DB state. Includes both
  `rm 3 --force` and `rm --force 3`, `day -1d`, and `add x --due -2d`. This is
  where controllers are covered.

### Delivery increments

Each slice after the first delivers commands you can run. It adds only the
logic, adapter, SQL, controller and rendering code those commands need, writing
that code's tests from "Test behaviors" first. Namespaces grow across slices.

1. **Runtime check and schema.** Install babashka. Create `bb.edn`,
   `components`, `diplomat.sqlite.db`, and the helpers fixture. Tests first: the
   schema test asserts `user_version` is 1, `tasks_fts` exists, and an FTS
   `MATCH` runs. Done when `bb test` passes and every runtime check under
   "Risks" has its result recorded in this spec.
2. **`add` and `list`.** Generator; `make-task`, `new-task`, `parent-error`,
   `progress`, `build-tree`; `parse-date-input` and `due-label`; the row and
   argv adapters; `insert!`, `find-by-id`, `list-open`, `list-all`; `add!` and `list-tasks`;
   `run`, `-main`, the message table, `tree-lines`; and `bin/gaoshun`. Done when
   `gaoshun add` and `gaoshun list` work from an unrelated directory, with color
   in a terminal and none when piped or with `NO_COLOR` set.
3. **Transitions.** `transition`, `update!`, `transition!`, and the four
   commands. Done when every cell of the transition table passes at the logic
   and integration levels.
4. **`edit` and `rm`.** `apply-edit`, `args->changes`, `count-children`,
   `delete!`, `edit!`, `remove!`. Done when the edit validation errors, both
   `rm --force` orders and the cascade pass.
5. **`day`.** `next-day`, `day-sections`, `day-rows`, `section-lines`, and the
   `day` command. Done when the section tests, the midnight boundary test and
   `day -1d` pass, and `EXPLAIN QUERY PLAN` shows index use (or the result is
   recorded under "Risks" if it doesn't).
6. **`search`.** `search-match`, the FTS sync tests, `search`, `flat-lines`, and
   the `search` command. Done when the FTS and punctuation tests pass.

## Decisions, risks, and traceability

### Key decisions

- **Path as the SQLite component:** forced by the pod's API. Rules out
  multi-statement transactions; every write that must be atomic is one statement.
- **Clock as a component:** keeps logic pure and tests deterministic, at the
  cost of passing `today` explicitly into date-aware logic.
- **FTS5 from day one:** no backfill later; the trade-off is word-prefix
  matching instead of substrings.
- **Idempotent transitions:** repeats succeed without writing; only real
  changes of intent are illegal.
- **Map-shaped errors everywhere:** one predicate, and messages that can name
  the id.
- **`list` reads one page from SQL:** `WHERE` and `LIMIT`/`OFFSET` with an
  `ORDER BY` that mirrors `build-tree`'s sort. Trades a second definition of
  the order (pinned to the first by a test) for not loading closed history on
  every `list`. Replaces the plan review's whole-table load after a 20,000-row
  measurement showed about 500 ms per `list`.
- **Vertical slices:** a usable `add` and `list` arrive in the second
  increment, at the cost of touching each layer's files several times.
- **Local date-times, not `Instant`:** the day view is about the calendar day as
  lived; `Instant` would move old tasks between days when the system time zone
  changes.

### Risks and open questions

Nothing here could be run while planning, because babashka is not installed.
Increment 1 checks the first three; the rest are covered by tests in the slice
named.

- **Pod result shapes:** the README shows `execute!` and `query` but not what
  `PRAGMA user_version` returns. Increment 1 records the actual shape before
  `ensure-schema!` depends on it.
  Result: `query` on `PRAGMA user_version` returns `[{:user_version 0}]`
  (keyword-keyed, one row). `execute!` on `PRAGMA user_version = 1` returns
  `{:rows-affected 0, :last-inserted-id 0}` and the next read shows 1.
  `execute!` on an `INSERT` returns `{:rows-affected 1, :last-inserted-id n}`,
  so `insert!` can read the id from `:last-inserted-id` as planned.
- **Busy timeout:** unknown whether the pod sets one. Increment 1 runs two
  overlapping writes. If the second fails, either pass a timeout through the
  connection string (if the pod accepts one) or accept the failure for a
  single-user tool and record that here.
  Result: the pod sets a busy timeout of about 5 seconds. A 14 second write
  (20M-row insert) in one process, with a second process writing 4 seconds in,
  made the second call wait 5008 ms and then fail with "database is locked".
  Short overlapping writes wait and succeed, so nothing needs changing for a
  single-user tool. No connection-string timeout was tried, since none is
  needed.
- **`?` binding:** increment 1 runs one query with repeated positional
  arguments to confirm the binding form used throughout.
  Result: works. `["SELECT * FROM t WHERE a = ? OR b = ? OR a = ?" "x" "nope" "x"]`
  binds each `?` positionally in order, and the same value can be passed for
  several placeholders. The `day` query's `[d next-day d]` is safe.
- **`bb --config` path resolution:** if babashka resolves `:paths` relative to
  the working directory instead of `bb.edn`, the wrapper breaks when run
  elsewhere. Increment 2's done condition tests exactly this; the fallback is
  `cd "$root"` in the wrapper, which is harmless because no command takes a
  relative path.
  Result: `bb --config /abs/bb.edn -e "(require 'gaoshun.components)"` run from
  an unrelated directory resolves `:paths` relative to the config file, and the
  pod from that file's `:pods` loads. The same require without `--config`
  fails with "Could not locate gaoshun/components...", so the config is what
  makes it work. The `cd` fallback is not needed.
- **Boolean flags:** the claim that an uncoerced flag swallows the next
  positional value comes from memory of `babashka.cli`, not from a run.
  Coercion costs nothing whether or not the claim holds; increment 4 tests
  both `rm` argument orders.
- **Leading-dash values:** `babashka.cli` may read `-1d` as a flag. Increments
  2 (`--due -2d`) and 5 (`day -1d`) test it; if the library claims them, the
  dispatch reads the raw value from the remaining args.
  Result (increment 2): `babashka.cli` does not claim a leading-dash value that
  follows an option. `["add" "x" "--due" "-2d"]` parses to
  `{:title "x" :due "-2d"}`, with or without `:coerce {:due :string}`, so no
  raw-args fallback is needed for `--due`. `day -1d` (a positional) is still
  untested and belongs to increment 5.

### Requirements traceability

- **Add task or subtask:** `add!`, `parent-error`, `insert!`; integration `add`
  tests, `logic.task` parent tests.
- **Four statuses, idempotent transitions:** `transition`, `transition!`;
  transition-table tests plus integration tests.
- **`finish_date` semantics:** `transition`, `make-task` invariant; `logic.task`
  and integration tests.
- **Pending list with tree, context parent, progress:** `list-open`,
  `build-tree`, `progress`, `tree-lines`; `logic.task` and integration `list` tests.
- **`list --all` ordering:** `build-tree`; `logic.task` sort tests; the
  sqlite test that the `ORDER BY` matches `build-tree`.
- **Paging:** `list-open`, `list-all`, `parse-page`; sqlite paging tests and
  integration `list --page` tests.
- **Day view, three sections:** `day` query, `day-sections`; sqlite boundary and
  `logic.task` section tests.
- **Full-text search:** FTS table and triggers, `search-match`; sqlite FTS sync
  tests, adapter quoting tests.
- **Relative colored due dates:** `due-label`, render, wrapper; `logic.date`
  boundary tests, increment 2 check.
- **Edit and delete:** `edit!`, `remove!`, cascade statement; integration and
  sqlite cascade tests.
- **Storage location and override:** `components`; the helpers fixture uses
  `GAOSHUN_DB` throughout.

### Standards and skills

- **`test-driven-development`:** each increment writes its listed tests first,
  watches them fail, then implements. Controllers have no unit tests; each
  slice's integration tests are their Red step.
- **`diplomat-architecture`:** the layer map is the review checklist. The rules
  most likely to be broken here: no `adapters` require in controllers, no
  `diplomat.sqlite.*` require in `diplomat.cli`, no SQL outside
  `diplomat.sqlite`, no printing outside `diplomat.cli`, whole-map assertions,
  and no tests aimed at controllers.

## Codebase references

### Files

The repository is empty apart from `.agents/`. Every file under
"Component changes" is created by this spec.

- **`.agents/tasks/gaoshun-task-cli/design.md` — Reference:** product behavior,
  display rules, `day` predicates.
- **`.agents/tasks/gaoshun-task-cli/design-review.md` — Reference:** why the day
  view has three sections and how progress is defined.

### Documentation

- **[pod-babashka-go-sqlite3](https://github.com/babashka/pod-babashka-go-sqlite3):**
  version 0.3.9; `execute!`/`query` take a file path; FTS5 compiled in; rows are
  keyword-keyed by column name.
- **[SQLite FTS5, external content tables](https://sqlite.org/fts5.html#external_content_tables):**
  the trigger pattern used in "Schema".

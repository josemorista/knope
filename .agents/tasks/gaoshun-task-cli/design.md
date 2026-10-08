# gaoshun: task and day-log CLI

## Purpose

A local command-line tool for logging what you did in a day and tracking what is
still open. Single user, single machine, no network, no server. Storage is a
SQLite file in the user's home directory.

The tool answers three questions:

- What am I supposed to be working on right now? (`list`)
- What did I finish on a given day, and what was I sitting on? (`day`)
- Where is that task I wrote down a while ago? (`search`)

## Scope

In scope: creating tasks and one level of subtasks, moving them through four
statuses, an optional due date with urgency-aware display, a pending list, a
per-day view, and full-text search.

Out of scope for this version: tags, a separate follow-up entity, arbitrary
nesting depth, derived parent status, JSON output, recurring tasks, time
tracking. Each was considered and cut; the rationale for
the ones most likely to be revisited is in "Decisions and rationale".

## Data model

One table, self-referencing for a single level of nesting.

```sql
CREATE TABLE IF NOT EXISTS tasks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id   INTEGER REFERENCES tasks(id),
  title       TEXT NOT NULL,
  note        TEXT,
  status      TEXT NOT NULL DEFAULT 'todo',   -- todo | doing | done | dropped
  created_at  TEXT NOT NULL,                  -- ISO-8601 local timestamp
  due_date    TEXT,                           -- ISO-8601 date (YYYY-MM-DD)
  finish_date TEXT                            -- ISO-8601 date (YYYY-MM-DD)
);

CREATE INDEX IF NOT EXISTS tasks_parent_idx     ON tasks(parent_id);
CREATE INDEX IF NOT EXISTS tasks_status_idx     ON tasks(status);
CREATE INDEX IF NOT EXISTS tasks_created_at_idx ON tasks(created_at);
CREATE INDEX IF NOT EXISTS tasks_finish_date_idx ON tasks(finish_date);
```

**Statuses.** `todo` on creation, `doing` for work in progress, `done` for
completed work, `dropped` for work abandoned on purpose. `dropped` exists so
that abandoning a task does not require deleting it, which would erase the fact
that it was ever on the plate.

**Nesting.** A task with a `parent_id` is a subtask. A subtask may not itself
have subtasks. This is enforced in `logic/task`, not by the schema, because the
check needs a lookup of the proposed parent.

**Parent status is manual.** Completing every subtask does not complete the
parent. A parent with subtasks displays progress (see "Display") so the signal
is visible without the automation.

**`finish_date` is the date a task left the pending list.** It is set on both
`done` and `dropped`, and nulled by `reopen`. Setting it on `dropped` is what
makes historical day views correct: a task dropped last Tuesday must stop
appearing as open in Wednesday's view. Whether the task was completed or
abandoned is read from `status`, never from `finish_date`.

**Timestamps.** `created_at` is a full local timestamp so same-day ordering is
preserved. `due_date` and `finish_date` are plain dates; neither has a
meaningful time-of-day.

## Commands

```
gaoshun add "title" [--due DATE] [--parent ID] [--note TEXT]   # prints the new id
gaoshun start ID | done ID | drop ID | reopen ID
gaoshun edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]
gaoshun rm ID [--force]
gaoshun list [--all] [--page N]
gaoshun day [DATE]
gaoshun search TEXT
```

`DATE` accepts ISO (`2026-10-01`), `today`, `tomorrow`, and day offsets (`+3d`),
parsed by one shared function so every command takes dates identically.

`reopen` returns a `done` or `dropped` task to `todo` and nulls `finish_date`.

`rm` is a hard delete for typos. It refuses to delete a parent that still has
subtasks unless `--force` is given, in which case the subtasks are deleted too.

`list` shows `todo` and `doing` tasks as a parent/child tree. `--all` also
includes `done` and `dropped`. Open tasks come first, sorted by due date
ascending with undated tasks last. Closed tasks follow, sorted by `finish_date`
descending so the most recently finished work reads first. Subtasks render
indented under their parent, ordered by the same rules. `list` shows 50
root tasks per page; `--page N` (0-based) moves through them.

`day` defaults to today and renders three sections, which are disjoint by
construction:

- **Done:** `status = 'done' AND finish_date = D`
- **Dropped:** `status = 'dropped' AND finish_date = D`
- **Open:** `created_at < :next-day AND (finish_date IS NULL OR finish_date > D)`

`:next-day` is D plus one day, computed in the adapter. Comparing the raw
`created_at` column against it, rather than wrapping the column in `date()`,
is what lets SQLite use `tasks_created_at_idx`. An ISO timestamp on day D
always sorts before the string for D+1, so the two forms are equivalent. The
sections are fetched in one query that ORs the `finish_date = D` condition with
the Open condition, so `tasks_finish_date_idx` exists to make both sides of
that `OR` indexable.

The day view answers "what did I finish", "what did I decide not to do", and
"what was I carrying". A day with progress but no completions still produces a
useful record, and the day a task is dropped is the day that decision shows up.
Empty sections are omitted.

`search` uses an FTS5 index over `title` and `note`. Each whitespace-separated
word in the input must match the start of a word in the task, case- and
accent-insensitively, so `auth serv` finds "Authentication service". Results
are ordered by relevance.

## Storage location

`$HOME/.gaoshun/tasks.db`, overridden by the `GAOSHUN_DB` environment variable.
The override is what makes the test suite possible (each test points at a temp
file) and doubles as an escape hatch for putting the database in a synced
folder.

The directory is created if missing and the schema is applied with
`CREATE TABLE IF NOT EXISTS` on every startup, so first run needs no setup step.
SQLite's `user_version` pragma is set to `1`. There is no migration framework;
when the schema first changes, a version check is added at that point.

## Runtime

Babashka, with SQLite access through `pod-babashka-go-sqlite3`. Babashka starts
in tens of milliseconds, which matters for a tool invoked dozens of times a day.
The pod downloads itself on first run and caches under `~/.babashka/pods`.

Rejected: JVM Clojure with `next.jdbc`, which pays roughly a second of startup
per invocation and would need GraalVM `native-image` plus its config to fix;
and shelling out to the `sqlite3` binary, which is not installed on this machine
either and would mean hand-rolling SQL escaping.

No external schema library. Structure is enforced by constructor functions
(see below), which also removes any babashka-compatibility risk from
`schema.core` or `malli`.

## Architecture

Diplomat layering, adapted to this project's two effect boundaries: the
terminal and SQLite. Each is a diplomat.

```
src/gaoshun/
  logic/task.clj              make-task, status transitions, nesting check,
                              sort order, parent progress
  logic/date.clj              offset arithmetic, relative-label computation
  controllers/task.clj        add! start! done! drop! reopen! edit! remove!
                              list day search
  adapters/task.clj           argv-map -> task, sqlite row -> task,
                              task -> display row
  diplomat/cli.clj            argv parsing, dispatch, error -> exit code
  diplomat/cli/render.clj     glyphs, indentation, color, TTY / NO_COLOR
  diplomat/sqlite/task.clj    every SQL statement for tasks
  diplomat/sqlite/db.clj      pod load, connection, schema, user_version
  components.clj              composition root

bin/gaoshun                   bash wrapper: exec bb --config <repo>/bb.edn
                              -m gaoshun.diplomat.cli "$@"
bb.edn                        :paths ["src"], deps, tasks (run, test)
```

There is no `models` layer. Without schemas it would hold nothing: the Task
shape is defined by `logic.task/make-task`, the constructor every code path
funnels through.

There are no `wire` namespaces. The wire concept survives as the raw maps
crossing the boundaries (parsed argv, SQLite rows); it has no files of its own
once the schemas are gone.

**Layer rules.** `logic` depends on nothing outside itself. `adapters` may use
`logic`. `controllers` may use `logic` and `diplomat/sqlite`, and must not
require `adapters`: by the time data reaches a controller it is already a task
model, and controllers return models or error values. Both diplomats may use
`adapters`. Nothing outside `diplomat/sqlite` contains SQL; nothing outside
`diplomat/cli` prints or sets an exit code.

**Dependency injection** is a plain map, `{:sqlite db-path :clock now-fn}`,
built in `components.clj` and passed down from the CLI diplomat into
controllers. The pod takes a database file path on every call rather than a
connection object, so the path is the SQLite component. The clock is a
zero-argument function returning the current local date-time; tests replace it
with a constant. No Component or Integrant library; neither component has a
lifecycle.

## Data flow

```
argv
  -> diplomat/cli        parses flags into a raw arg map
  -> adapters/task       validates input, builds a task via logic.task/make-task
  -> controllers/task    orchestrates: lookup, apply rules, persist
  -> diplomat/sqlite     executes SQL, returns rows
  -> adapters/task       row -> task
  <- controllers/task    returns models or error values
  -> adapters/task       task -> display row (glyph chosen, due date already
                         rendered as "2d overdue" by logic.date)
  -> diplomat/cli/render concatenates and colors strings
  -> stdout
```

Date handling spans two layers on purpose. Parsing `+3d` from argv into a real
date is a wire-to-model coercion and lives in `adapters/task`, while the
arithmetic and label computation are pure and live in `logic/date`. This keeps
ad-hoc coercions out of the diplomats.

## Display

`list` renders parents flush-left and subtasks indented two spaces, each line
carrying a status glyph, the id, the title, and the relative due date.

A parent with subtasks shows progress as `done/planned`. The numerator counts
subtasks with `status = 'done'`. The denominator counts subtasks whose status is
not `dropped`, because a dropped subtask is work removed from the plan and
should not inflate what remains. A parent whose subtasks are all dropped shows
no progress indicator.

Due dates render relatively (`today`, `tomorrow`, `in 3d`, `2d overdue`),
falling back to the absolute date beyond seven days out, where a relative offset
stops being legible. Color encodes urgency: red for overdue, yellow for today or
tomorrow, dim otherwise. Undated tasks show nothing.

Color is emitted only when stdout is a TTY and `NO_COLOR` is unset, so redirected
output stays clean.

Output is human-readable text only. There is no `--json`; the SQLite file itself
is a better scripting interface than a flag that would go unmaintained.

## Error handling

Controllers return errors as values, never as exceptions and never as
transport-shaped responses:

- `:task/not-found`
- `:task/nesting-too-deep`
- `:task/has-subtasks`
- `:task/illegal-transition`
- `{:error/code :validation/bad-date :error/details {...}}`

`diplomat/cli` owns the single `case` that maps each value to a one-sentence
message on stderr and exit code 1. Success exits 0.

`logic.task/make-task` **throws** when an invariant is violated: an unknown
status keyword, a subtask given a subtask parent, a `done` or `dropped` task
with no `finish_date`, or a `todo` or `doing` task that has one. Reaching it
with bad data is a programming error rather than a user error. User input never
gets that far: the adapter validates argv first and returns an error value.
Genuine bugs print a stack trace so they are debuggable.

Empty results are not errors. `list` with nothing pending prints
`Nothing pending.` and exits 0.

## Testing

`bb test` runs `clojure.test` against a temp database supplied through
`GAOSHUN_DB`. A generator lives in `test/gaoshun/gen/task.clj` and is written
before the tests that consume it; tests assert on whole maps with `=` rather
than key by key.

```
test/gaoshun/
  gen/task.clj
  logic/task_test.clj
  logic/date_test.clj
  adapters/task_test.clj
  diplomat/sqlite/task_test.clj    real SQL against a temp DB
  integration/cli_test.clj         argv in, stdout and DB state out
```

`logic/date` gets the densest unit coverage, since date parsing and relative
formatting have the most edge cases per line in the project. Controllers are not
unit-tested: they are orchestration glue, and their behavior is covered by the
integration test.

## Delivery sequence

1. Install babashka and verify `pod-babashka-go-sqlite3` loads and runs a query.
   A failure here changes the design, so it comes before any code.
2. `add` and `list`, end to end, with the `bin/gaoshun` wrapper.
3. The status transitions.
4. `edit` and `rm`.
5. `day`.
6. `search`.

Each step after the first is a vertical slice through every layer it needs.
`techspec.md` holds the per-slice detail.

## Decisions and rationale

**One entity with self-referencing subtasks, one level deep.** Arbitrary depth
would require a recursive renderer and a cascade rule for status; one level
keeps every command a flat id lookup.

**No follow-up entity and no tags.** A follow-up is an open task, and when it
follows up on something specific it is a subtask of that thing. A `tag` column
is the least-regret addition if grouping is ever wanted; it is a small change to
the schema and one filter flag.

**FTS5 from day one.** The `pod-babashka-go-sqlite3` README states the pod is
built with the `json1` and `fts5` features, so FTS5 carries no availability
risk. Starting with it on an empty database avoids a later backfill. The cost
is one virtual table and three sync triggers, all confined to
`diplomat/sqlite`, plus query escaping in the adapter. Matching is by word
prefix rather than arbitrary substring, which is the expected behavior for
"find that task about auth".

**Diplomat layering for a nine-namespace CLI.** The layering is heavier than a
single-entity personal tool strictly needs; the payoff is a structure matching
the one used day to day, plus clean separation between SQL, rules, and
rendering. This was chosen deliberately with the cost understood.

---
feature: gaoshun-task-cli
reviewed: .agents/tasks/gaoshun-task-cli/techspec.md
requirements: .agents/tasks/gaoshun-task-cli/design.md
verdict: changes-requested
findings: { blocker: 0, warning: 6, info: 4 }
axes: [grounding, coverage-risk, simplicity]
axes-skipped: []
panel: [grounding: grounding-reviewer, coverage-risk: coverage-risk-reviewer, simplicity: simplicity-reviewer]
---

# Plan review: gaoshun task CLI

## Verdict

Safe to implement once six warnings are settled, none of them structural. The most important
one is a layer violation: `diplomat.cli` calls `diplomat.sqlite.db/ensure-schema!` directly,
which the Diplomat layer map forbids and which the spec's own standards section says to check.

## Coverage

- **Axes run:** grounding checked the spec against the design and the diplomat-architecture
  layer map; coverage-risk walked design requirements to spec to tests and probed failure
  modes; simplicity looked for removable mechanisms and increment order.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the repository is greenfield and babashka is not
  installed, so no reviewer could run the pod or `babashka.cli`. Every claim about their
  runtime behavior (boolean flag parsing, named parameter binding, busy timeout, FTS5
  handling of token-less phrases) is from memory and unverified. The grounding reviewer did
  not open the test-driven-development skill.
- **Design gate:** `design.md` went through spec-detective (`design-review.md`), requirements
  axis only. The domain axis was skipped for lack of Glean and Atlassian.

## Findings

### WARNING-1 — The CLI diplomat calls the SQLite diplomat directly
- **Axis:** grounding
- **Where:** techspec "Component changes", `diplomat/cli.clj`
- **Problem:** `run` calls `ensure-schema!` in `diplomat.sqlite.db`. The layer map lets an
  entry-point diplomat use adapters, wire, controllers, and models, not another diplomat.
- **Evidence:** techspec line 235, "`run` calls `ensure-schema!`"; diplomat-architecture
  layer map row `diplomat-server | adapters, wire, controller, model`.
- **Proposed change:** run `ensure-schema!` inside `components/components` when it builds the
  map. The composition root is allowed to know every diplomat. `run` then requires no
  `diplomat.sqlite.*` namespace, and the test fixture gets schema setup by building
  components.
- **Verified:** confirmed. Line 235 says exactly that, and the layer map row is as quoted.

### WARNING-2 — Output for `add`, `edit`, `rm` and the `day` headers is unspecified
- **Axis:** coverage-risk
- **Where:** techspec "Shared contracts"; design.md line 73
- **Problem:** the integration tests assert `:out` for every command, but the spec gives
  success text only for transitions. The implementer would invent the rest.
- **Evidence:** design.md:73 `# prints the new id`; no `add`, `edit` or `rm` output in the
  techspec (searched for "Added task", "Updated task", "Deleted task": no hits).
- **Proposed change:** `add` prints `Added task 12.`, `edit` prints `Updated task 12.`, `rm`
  prints `Deleted task 12.` or `Deleted task 12 and 2 subtasks.`, and `day` prints a
  `2026-09-28` line followed by `Done`, `Dropped`, `Open` headings over indented flat lines.
- **Verified:** confirmed by searching both files.

### WARNING-3 — Search input with no letters or digits is unhandled
- **Axis:** coverage-risk
- **Where:** techspec "Queries", `adapters.task/search-match`
- **Problem:** `search-match` only drops empty words, so `gaoshun search -` becomes `"-"*`.
  How FTS5 treats a phrase with no tokens is unknown; if it raises a syntax error, `run`
  catches nothing and the user sees a stack trace.
- **Evidence:** techspec lines 188-190 describe the only filtering applied.
- **Proposed change:** drop words that contain no letter or digit; if nothing remains,
  return `:validation/empty-search`. Add a sqlite diplomat test that runs a punctuation-only
  query against the real index.
- **Verified:** the spec gap is confirmed. The FTS5 runtime behavior is unverified, and the
  fix removes the need to know it.

### WARNING-4 — `babashka.cli` may read the id after a boolean flag as the flag's value
- **Axis:** grounding
- **Where:** techspec "Component changes", `diplomat/cli.clj` ("no coercion")
- **Problem:** without boolean coercion, `gaoshun rm --force 3` would likely parse as
  `{:force "3"}` with no id. Same for `--all` and `--clear-due`.
- **Evidence:** unverified, no checkable citation; reviewer and author both recall this
  as `babashka.cli`'s documented behavior for flags followed by a value.
- **Proposed change:** coerce only `--force`, `--all` and `--clear-due` to `:boolean`; every
  value-bearing option stays a string. Add `rm --force 3` next to `rm 3 --force` in the
  integration tests.
- **Verified:** unverified. Carried as an assertion; the fix costs one map entry either way.

### WARNING-5 — `list` has a family query that `build-tree` makes unnecessary
- **Axis:** simplicity
- **Where:** techspec "Queries" (list CTE), `list-families`
- **Problem:** `build-tree` already filters by open status, marks context parents, and hides
  closed children. The CTE does the same selection in SQL, which means a second function,
  a second test, and the selection rule defined in two places.
- **Evidence:** techspec "Component changes", `build-tree` bullet; `list --all` already uses
  `SELECT * FROM tasks` with the same builder.
- **Proposed change:** drop the CTE and `list-families`; `list` and `list --all` both load
  every task and let `build-tree` decide. Cost: `list` reads the whole table, including
  history, which is thousands of rows at most.
- **Verified:** confirmed against the spec's own `build-tree` description.

### WARNING-6 — Delivery increments are horizontal layers
- **Axis:** simplicity
- **Where:** techspec "Delivery increments"
- **Problem:** increments 2 to 4 build all logic, then all adapters, then all SQL, with no
  runnable command until increment 5 and nothing visible until 6.
- **Evidence:** increment list as written.
- **Proposed change:** after the runtime check, build vertical slices: `add` + `list`
  (with the wrapper), then transitions, then `edit` + `rm`, then `day`, then `search`. Each
  slice adds only the logic, adapter, SQL and CLI code it needs, tests first.
- **Verified:** confirmed from the increment list.

### INFO-1 — The `created_at` index is not used by the `day` query
- **Axis:** grounding
- **Where:** techspec "Queries" (day)
- **Problem:** the query ORs `finish_date = :d` with the `created_at` range. `finish_date`
  has no index, so SQLite scans the table and `tasks_created_at_idx` goes unused.
  Correctness is unaffected.
- **Proposed change:** add `tasks_finish_date_idx` so both OR branches are indexable, and
  confirm with `EXPLAIN QUERY PLAN` in the day slice. Alternatively, drop the index claim.
- **Verified:** light check; the reasoning follows from SQLite's OR optimization rules.
- **Reopens a settled decision:** yes, the rationale for the `created_at` index.

### INFO-2 — Named parameters versus the pod's positional arguments
- **Axis:** grounding
- **Where:** techspec "Queries"
- **Problem:** the README only shows `?` with positional arguments. The spec's reads use
  `:d`, `:next_day`, `:q`.
- **Proposed change:** write every query with `?` and repeat values positionally.
- **Verified:** light check against the README example.

### INFO-3 — Concurrent invocations may fail rather than wait
- **Axis:** coverage-risk
- **Where:** techspec "Failure and operational behavior", Concurrency
- **Problem:** SQLite only waits on a lock when a busy timeout is set, and a per-call
  connection would not keep a `PRAGMA`. Whether the pod sets one by default is unknown.
- **Proposed change:** add a two-writer check to increment 1 and record the result.
- **Verified:** light check; the spec's claim is unconditional.

### INFO-4 — `GAOSHUN_TTY` can leak from the parent environment
- **Axis:** coverage-risk
- **Where:** techspec `bin/gaoshun`
- **Problem:** the wrapper only sets `GAOSHUN_TTY=1`, so an exported value survives piping.
- **Proposed change:** set it to `1` or `0` unconditionally.
- **Verified:** light check. The reviewer also flagged the wider date grammar; that was
  approved in planning and is not a finding.

## Resolution

The developer asked for every finding to be addressed with its proposed change.
All ten are resolved in `techspec.md`, and `design.md` was updated where the
design itself described the changed behavior.

| Finding | Outcome |
|---|---|
| WARNING-1 | Resolved. `components` runs `ensure-schema!`; `diplomat.cli` requires no `diplomat.sqlite.*` namespace, and the standards section now lists that rule. |
| WARNING-2 | Resolved. "Other command output" in Shared contracts specifies `add`, `edit`, `rm`, `day` and `search` output. |
| WARNING-3 | Resolved. `search-match` drops words with no letter or digit and returns `:validation/empty-search` when none remain; adapter and sqlite tests added. |
| WARNING-4 | Resolved. `--force`, `--all` and `--clear-due` are coerced to booleans; both `rm` argument orders are tested. The underlying `babashka.cli` claim stays unverified and is listed under Risks. |
| WARNING-5 | Resolved. The family CTE and `list-families` are removed; `list` loads every task and `build-tree` owns selection. Recorded as a key decision with its trade-off. |
| WARNING-6 | Resolved. Delivery is now six increments: runtime check, then vertical slices `add`+`list`, transitions, `edit`+`rm`, `day`, `search`. `design.md`'s delivery sequence was updated to match. |
| INFO-1 | Resolved. `tasks_finish_date_idx` added to the design schema so both sides of the `day` `OR` are indexable; the `day` slice confirms with `EXPLAIN QUERY PLAN`. |
| INFO-2 | Resolved. All queries use `?`; increment 1 checks repeated positional binding. |
| INFO-3 | Resolved. Increment 1 runs two overlapping writes and records the result; the concurrency bullet no longer claims locking is automatic. |
| INFO-4 | Resolved. The wrapper sets `GAOSHUN_TTY` to `1` or `0` unconditionally. |

No finding was overruled, so no blocker carries into the handoff. None of the
revisions was structural, so no second review pass is needed.

## Discarded

None — every finding survived verification.

## Verified clean

The `day` predicates and fixed `created_at` string ordering, the `finish_date` invariants
against the design, the FTS trigger set and prefix quoting, the single-statement cascade, and
the absence of controller, model or wire tests.

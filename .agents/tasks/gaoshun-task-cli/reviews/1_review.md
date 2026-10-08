---
feature: gaoshun-task-cli
reviewed: task 1.0, working tree vs HEAD (5d84cfc) on feat/add-and-list
diff-command: git diff HEAD && git ls-files --others --exclude-standard -- bb.edn src test
spec: .agents/tasks/gaoshun-task-cli/techspec.md
verdict: clean
findings: { blocker: 0, warning: 0, info: 3 }
axes: [spec, correctness, standards, alternatives]
axes-skipped: []
panel: [spec: sonnet, correctness: opus, standards: haiku, alternatives: sonnet]
---

# Review: task 1.0, runtime check and schema

## Verdict

Acceptable as-is. The schema, `components` and the test runner match the
techspec, and the recorded runtime results break no later assumption. Three Info
findings; one is bookkeeping, resolved at record time, and two stay open for the
revision session.

## Scope

- **Reviewed:** `bb.edn`, `src/gaoshun/components.clj`,
  `src/gaoshun/diplomat/sqlite/db.clj`, `test/gaoshun/helpers.clj`,
  `test/gaoshun/diplomat/sqlite/db_test.clj`.
- **Set aside:** `.clj-kondo/` and `.lsp/` (untracked editor caches, not part of
  the task). The `techspec.md` and `1_task.md` edits are spec artifacts; the
  spec axis checked that the four "Result:" lines exist.

## Coverage

- **Axes run:**
  - spec: walked every task and techspec requirement both ways; no drift.
  - correctness: schema idempotency, interrupted-run repair, FTS DDL against the
    techspec, the test task's exit code, callers of `ensure-schema!`.
  - standards: diplomat-architecture layer map for every require, and TDD
    compliance.
  - alternatives: size against requirement, existing mechanisms, test runner.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the spec reviewer could not independently
  re-run the recorded runtime checks (busy timeout in particular). The
  correctness reviewer could not confirm how the pod treats an empty path
  (INFO-3).
- **Not covered by this gate:** the test suite. CI runs it; this pass hunts what
  a green suite doesn't show.

## Findings

### INFO-1 — Acceptance criteria boxes left unchecked
- **Axis:** spec-adherence
- **Location:** `.agents/tasks/gaoshun-task-cli/1_task.md:95-100`
- **Problem:** every Todo is checked but the five acceptance boxes were not.
- **Evidence:** each Todo is backed by the diff; `bb test` is green.
- **Fix:** tick the acceptance boxes.
- **Verified:** read the task file; boxes were `[ ]`.
- **Status:** resolved (ticked when the batch run recorded the task).

### INFO-2 — Nothing checks the type of the real clock
- **Axis:** correctness
- **Location:** `test/gaoshun/diplomat/sqlite/db_test.clj:24,34`
- **Problem:** line 24 asserts the fixture's own `(constantly fixed-now)`, which
  `helpers.clj:19-20` swaps in, so it passes whatever clock `components`
  builds. The only check on the real clock is `(fn? (:clock built))` at line 34.
- **Evidence:** if `components.clj:15` returned `(LocalDate/now)`, the suite
  stays green, while techspec § Shared contracts requires `:created-at` to be a
  `LocalDateTime` for the fixed `created_at` format.
- **Fix:** at line 34 assert `(instance? LocalDateTime ((:clock built)))`; drop
  the line-24 clock assertion.
- **Verified:** light check; read `helpers.clj:15-23` and `db_test.clj:22-34`,
  both as described.
- **Status:** open (Info, tests were written by the test writer and are not
  changed in the fix round).

### INFO-3 — Empty or relative `GAOSHUN_DB` is used as given
- **Axis:** correctness
- **Location:** `src/gaoshun/components.clj:7`
- **Problem:** `(or (get env "GAOSHUN_DB") ...)` treats `""` as set and passes a
  relative path through; techspec § Components shows an absolute path.
- **Evidence:** with `GAOSHUN_DB=` exported empty, `:sqlite` is `""`. SQLite
  opens an empty filename as a private temporary database, so the schema could
  land in a database that vanishes per call. Pod behavior on `""` unverified.
- **Fix:** `(if-let [p (not-empty (get env "GAOSHUN_DB"))] (str (fs/absolutize p)) <home fallback>)`.
- **Verified:** light check; read `components.clj:6-8`. Pod behavior on `""`
  unverified.
- **Status:** open.

## Requirements ledger

| Requirement (spec section) | Status | Evidence |
|---|---|---|
| `bb.edn` has `:paths ["src"]` and the pod pinned at 0.3.9 (Component changes) | met | `bb.edn:1-2` |
| `bb test` adds `test/`, loads every test ns, exits non-zero on failure or error (Component changes) | met | `bb.edn:3-23` |
| `components` resolves path from `GAOSHUN_DB`, else `$HOME/.gaoshun/tasks.db` (Components) | met | `components.clj:6-8` |
| `components` calls `ensure-schema!` and returns `{:sqlite :clock}` (Component changes) | met | `components.clj:12-15` |
| `ensure-schema!` creates the parent dir, reads `user_version`, one `execute!` per statement on 0, then sets 1 (Schema) | met | `db.clj:38-44` |
| Statements keep `IF NOT EXISTS` (Schema) | met | `db.clj:6-33` |
| `tasks` table and four indexes as in the design (Schema, design.md) | met | `db.clj:6-19` |
| FTS table and three triggers copied exactly (Schema) | met | `db.clj:20-33` |
| Only `diplomat.sqlite.db` requires the pod; `components` is the composition root (System shape) | met | `db.clj:3`; `components.clj:3` |
| Schema test: `user_version` 1, `tasks_fts` exists, FTS MATCH runs (Delivery increment 1) | met | `db_test.clj:38-39,55-56,58-64` |
| Second `ensure-schema!` call is a no-op (task 1.3) | met | `db_test.clj:66-71` |
| Fixture builds components via `components` with temp path and fixed clock (Test behaviors) | met | `helpers.clj:11-23` |
| Risk result: `PRAGMA user_version` shape | met | `techspec.md:397-400` |
| Risk result: busy timeout | met | `techspec.md:405-410` |
| Risk result: `?` binding with repeated value | met | `techspec.md:411-415` |
| Risk result: `bb --config` path resolution | met | `techspec.md:421-425` |
| Acceptance criteria boxes ticked (task file) | met after record | INFO-1 |
| Non-goal: no task logic, adapters or CLI code | met | no such files in the diff |

## Discarded

None — every finding survived verification.

## Verified clean

Schema creation is idempotent and repairs an interrupted run, because
`user_version` is written only after every `IF NOT EXISTS` statement. The FTS
triggers match the techspec verbatim, and the layer map holds: only
`diplomat.sqlite.db` requires the pod, and `components` is the only caller.

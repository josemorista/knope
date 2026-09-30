---
name: runtime-check-and-schema
overview: "Install babashka, prove the SQLite pod works, create the schema including FTS5 through components, and record the result of every runtime risk in the techspec."
isProject: false
---

# Task 1.0: Runtime check and schema

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `bb test` runs a schema test against a real temp database, and
  the techspec's "Risks and open questions" section records what each runtime
  check found.
- **Scope:** `bb.edn`, `components`, `diplomat.sqlite.db`, the test fixture, and
  a one-off set of runtime checks.
- **Why this task:** every later task depends on the pod API, the schema and the
  fixture. A failure here changes the design, so it is found before feature code.
- **Ship group:** A, with 2.0 and 3.0.
- **State at the boundary:** suite green with one schema test. No CLI entry
  point exists yet.
- **Dependencies:** None. Needs network access once, to install babashka and
  download the pod.
- **Review focus:** the recorded risk results, since several later tasks rely
  on them.
- **Non-goals:** any task logic, adapters or CLI code.

<skills>
## Standard skills compliance

- **`test-driven-development`:** write the schema test first and watch it fail
  before `ensure-schema!` exists.
- **`diplomat-architecture`:** `diplomat.sqlite.db` is the only namespace here
  that requires the pod; `components` is the composition root that calls it.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Pod and bb config | § Component changes (`bb.edn`) | Pin `org.babashka/go-sqlite3` to `0.3.9`; `test` task exits non-zero on failure. |
| Schema creation | § Data and storage, "Schema" | Guard on `user_version = 0`, one `execute!` per statement, keep `IF NOT EXISTS`, then set `user_version = 1`. |
| Table and indexes | design.md § Data model | Four indexes, including `tasks_finish_date_idx`. |
| Components | § Shared contracts, "Components" | `(components env)` resolves the path, runs `ensure-schema!`, returns `{:sqlite path :clock now-fn}`. |
| Runtime risks | § Risks and open questions | Each check's result is written back into that section. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **1.1 Install babashka**
  - Change: none. The user installed babashka v1.13.225 before the run.
  - Verify: `bb --version` prints a version.
- [ ] **1.2 Project config**
  - Change: create `bb.edn` with `:paths ["src"]`, the pinned pod, and a `test`
    task that adds `test/` to the path, loads every `*-test` namespace, runs
    them, and exits 1 on any failure or error.
  - Verify: `bb test` runs and reports zero tests.
- [ ] **1.3 Schema test first**
  - Change: `test/gaoshun/helpers.clj` exposes a fixture that builds components
    through `gaoshun.components/components` with `GAOSHUN_DB` pointing at a temp
    file, then replaces `:clock` with a constant. Write
    `test/gaoshun/diplomat/sqlite/db_test.clj` asserting `user_version` is 1,
    `tasks_fts` exists, an FTS `MATCH` query runs, and a second
    `ensure-schema!` call is a no-op.
  - Verify: the test fails because the namespaces don't exist.
- [ ] **1.4 `diplomat.sqlite.db/ensure-schema!`**
  - Change: create the parent directory, read `PRAGMA user_version`, and on 0
    run the table, four indexes, FTS table and three triggers, then set the
    version. Copy the FTS DDL exactly from the techspec's "Schema" block.
  - Verify: the schema test passes.
- [ ] **1.5 `gaoshun.components/components`**
  - Change: path from `GAOSHUN_DB`, else `$HOME/.gaoshun/tasks.db`; call
    `ensure-schema!`; return the map with `(fn [] (LocalDateTime/now))`.
  - Verify: the fixture builds components against a temp path.
- [ ] **1.6 Runtime checks, recorded**
  - Change: in a scratch REPL or throwaway test, check each item and write the
    result into techspec § Risks and open questions:
    - the exact shape `query` returns for `PRAGMA user_version`
    - one query with the same value bound twice through `?`
    - two overlapping writes: does the second wait or fail with
      "database is locked"
    - `bb --config /abs/path/bb.edn` run from another directory resolves
      `:paths` relative to the config file
  - Verify: each Risks bullet has a "Result:" line. If one result breaks an
    assumption later tasks rely on, stop and raise it with the user.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] `bb test` passes with the schema test.
- [ ] A fresh temp database ends with `user_version = 1`, the `tasks` table,
  four indexes, `tasks_fts` and three triggers.
- [ ] Running `ensure-schema!` twice changes nothing.
- [ ] All four runtime checks have recorded results in the techspec.
- [ ] The tree is green at the boundary: build passes, suite passes.

## Task tests

- **Integration / contract:** schema test against a real temp database via the
  fixture.

## Open questions

None. babashka is installed.

## Relevant files

- **Create:** `bb.edn` — paths, pod pin, test task
- **Create:** `src/gaoshun/components.clj` — composition root
- **Create:** `src/gaoshun/diplomat/sqlite/db.clj` — `ensure-schema!`
- **Create:** `test/gaoshun/helpers.clj` — temp-DB fixture with fixed clock
- **Create:** `test/gaoshun/diplomat/sqlite/db_test.clj` — schema test
- **Modify:** `.agents/tasks/gaoshun-task-cli/techspec.md` — record risk results

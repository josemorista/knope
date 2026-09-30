---
name: day-view
overview: "Add `gaoshun day [DATE]`: one query fetches candidates, pure logic splits them into Done, Dropped and Open, and the renderer prints non-empty sections."
isProject: false
---

# Task 7.0: `day` view

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `gaoshun day` prints today's date and the non-empty sections;
  `day yesterday`, `day -1d` and `day 2026-09-01` work; a task created at
  23:59:59 on D is Open on D and absent on D-1.
- **Scope:** `next-day`, `day-sections`, `day-rows`, `render/section-lines`,
  the `day` controller and command, and an `EXPLAIN QUERY PLAN` check.
- **Why this task:** delivers the "what did I do on this day" view.
- **Ship group:** D, with 8.0.
- **State at the boundary:** suite green.
- **Dependencies:** 5.0 (finished and dropped tasks for the tests).
- **Review focus:** the three predicates must be disjoint; a task finished on D
  must not also appear in Open.
- **Non-goals:** nesting inside sections; lines are flat with `↳ #N`.

<skills>
## Standard skills compliance

- **`test-driven-development`:** `day-sections` cases before implementation.
- **`diplomat-architecture`:** the adapter formats D and D+1 for the query;
  the SQL lives only in `diplomat.sqlite.task`.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Sections | design.md § Commands (`day`) | Done, Dropped, Open; disjoint; empty ones omitted. |
| Query | § Data and storage, "Queries" | Use the `day` statement verbatim with args `[d next-day d]`. |
| Index use | § Data and storage, "Queries" | Run `EXPLAIN QUERY PLAN`; record the result under Risks if it scans. |
| Output | § Shared contracts, "Other command output" | Date line, headings, two-space indented flat lines; `Nothing recorded for D.` when all empty. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **7.1 `next-day` and `day-sections`**
  - Change: `logic.date/next-day`; `logic.task/day-sections [tasks day]`
    returning `{:done :dropped :open}` per the design's predicates, each list
    ordered by id.
  - Verify: finished on D, dropped on D, open across D, created after D,
    finished before D, and finished after D (Open on D).
- [ ] **7.2 SQLite `day-rows`**
  - Change: the `day` query with positional args.
  - Verify: a task created at `D T23:59:59` is returned for D and not for D-1;
    one created at `D+1 T00:00:00` is not returned for D.
- [ ] **7.3 Index check**
  - Change: in the sqlite test namespace, run `EXPLAIN QUERY PLAN` on the query
    and assert (or record) that it uses the indexes.
  - Verify: result recorded in techspec § Risks if it shows a full scan.
- [ ] **7.4 Controller, render and command**
  - Change: `controllers.task/day [date components]`; `section-lines` using
    display rows with `:parent-ref`; CLI `day [DATE]` defaulting to today,
    parsing DATE with `parse-date-input`.
  - Verify: integration tests below.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] A day with one done, one dropped and one open task prints all three
  sections in the order Done, Dropped, Open.
- [ ] A day with nothing prints `Nothing recorded for D.` and exits 0.
- [ ] `day -1d` and `day yesterday` show the previous day. If `babashka.cli`
  claims `-1d` as a flag, apply the Risks fallback.
- [ ] `day fri` exits 1 with the bad-date message.
- [ ] The tree is green at the boundary.

## Task tests

- **Unit:** `day-sections` cases; `next-day` at month and year end.
- **Integration / contract:** midnight boundary against a temp DB; query plan.
- **End-to-end:** the acceptance criteria above.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/logic/date.clj`, `src/gaoshun/logic/task.clj`, `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`, `src/gaoshun/diplomat/cli/render.clj`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

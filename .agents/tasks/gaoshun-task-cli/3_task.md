---
name: flat-list-and-wrapper
overview: "Add `list` and `list --all` for top-level tasks with relative, colored due dates, plus the `bin/gaoshun` wrapper that makes the tool runnable from anywhere."
isProject: false
---

# Task 3.0: Flat `list` and the wrapper

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `gaoshun list` from any directory shows open tasks sorted by due
  date with labels like `2d overdue`, in color on a terminal and plain when
  piped.
- **Scope:** `due-label`, `build-tree` for top-level tasks only,
  `task->display-row`, `list-all`, `list-tasks`, `render/tree-lines`, color
  detection, `bin/gaoshun`.
- **Why this task:** completes ship group A: you can add tasks and see them.
- **Ship group:** A, with 1.0 and 2.0.
- **State at the boundary:** suite green; `add` and `list` work through the
  wrapper. Subtasks don't exist yet, so `build-tree` handles roots only.
- **Dependencies:** 2.0.
- **Review focus:** sort order and the `due-label` boundaries.
- **Non-goals:** nesting, progress and context parents (4.0).

<skills>
## Standard skills compliance

- **`test-driven-development`:** Red-Green-Refactor per todo.
- **`diplomat-architecture`:** rendering and color live only in
  `diplomat.cli.render`; the adapter computes the label and urgency.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Due labels | § Shared contracts, "Relative due label" | Boundaries at -1, 0, 1, 2, 7, 8 days. |
| Tree building | § Component changes (`logic/task.clj`, `build-tree`) | Implement the sort rules now; grouping of children comes in 4.0. |
| List query | § Data and storage, "Queries" | `list` and `list --all` both use `SELECT * FROM tasks`. |
| Display rows | § Component changes (`adapters/task.clj`) | Glyph, id, title, due label and urgency now; progress and parent ref in 4.0. |
| Color | § Component changes, "Color detection" | Color when `GAOSHUN_TTY=1` and `NO_COLOR` unset. |
| Wrapper | § Component changes (`bin/gaoshun`) | Resolve symlink; set `GAOSHUN_TTY` to 1 or 0 unconditionally. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [x] **3.1 `logic.date/due-label`**
  - Change: `{:text :urgency}` per the contract.
  - Verify: each boundary, and nil due date returns nil.
- [x] **3.2 `logic.task/build-tree` (roots only)**
  - Change: without `:all?` keep open tasks; with it keep all. Open before
    closed; open by due ascending with nil last, then id; closed by finish date
    descending, then id descending. Return entries shaped so 4.0 can add
    `:children` and `:context?` without changing callers.
  - Verify: sort tests using generated tasks.
- [x] **3.3 Display rows and render**
  - Change: `adapters.task/task->display-row [task today extras]` with the four
    glyphs; `diplomat/cli/render.clj` `tree-lines` with ANSI red, yellow and
    dim, and `Nothing pending.` when empty.
  - Verify: adapter test for each status and urgency.
- [x] **3.4 `list-all`, `list-tasks`, and the `list` command**
  - Change: SQLite `list-all`; controller `list-tasks [all? components]`
    returns the tree; CLI adds `list` with `--all` coerced to boolean; `-main`
    computes `:color?`.
  - Verify: integration tests below.
- [x] **3.5 `bin/gaoshun`**
  - Change: bash wrapper per the techspec; mark executable; symlink into
    `~/.local/bin`.
  - Verify: the manual checks in Acceptance criteria. If `:paths` resolves
    against the working directory, apply the `cd "$root"` fallback from Risks.
- [x] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [x] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [x] `list` shows only `todo`/`doing` tasks in the specified order.
- [x] `list --all` places closed tasks after open ones (closed rows come from
  generated data inserted directly until 5.0 adds transitions).
- [x] An empty database prints `Nothing pending.` and exits 0.
- [ ] Manual: `gaoshun list` from `/tmp` works; it prints color in a terminal
  and none with `| cat` or `NO_COLOR=1`.
- [x] The tree is green at the boundary.

## Task tests

- **Unit:** `due-label` boundaries; `build-tree` sorting; display rows.
- **End-to-end:** `list` with mixed due dates, `list --all`, empty list, and
  `:color? false` output containing no escape codes.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/logic/date.clj`, `src/gaoshun/logic/task.clj`, `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`
- **Create:** `src/gaoshun/diplomat/cli/render.clj`
- **Create:** `bin/gaoshun`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

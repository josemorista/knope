---
name: subtasks
overview: "Support one level of subtasks: `add --parent`, the nesting rules, and `list` rendering subtasks indented under their parent with progress and context-parent headers."
isProject: false
---

# Task 4.0: Subtasks

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `add "step" --parent 1` creates a subtask; nesting under a
  subtask or a missing id fails with the spec's messages; `list` shows
  subtasks indented two spaces with `done/planned` progress on the parent.
- **Scope:** `parse-id`, `parent-error`, `progress`, `build-tree` grouping and
  context parents, display-row progress and `:parent-ref`.
- **Why this task:** completes the data model. 5.0 needs subtasks to show the
  dimmed parent header end to end; 6.0 needs them for the cascade.
- **Ship group:** B, with 5.0.
- **State at the boundary:** suite green; `list` nests. Closed parents can't
  be produced through the CLI until 5.0, so the context header is covered at
  the logic level here and end to end in 5.0.
- **Dependencies:** 3.0.
- **Review focus:** `build-tree` without `:all?`: which roots are kept, which
  children are hidden, and that progress counts every non-dropped child.
- **Non-goals:** rendering `:parent-ref` (7.0 and 8.0 use it).

<skills>
## Standard skills compliance

- **`test-driven-development`:** Red-Green-Refactor per todo.
- **`diplomat-architecture`:** the parent lookup happens in the controller;
  `parent-error` stays pure and takes the looked-up parent.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Nesting check | § Shared contracts, "Task map" (refinement paragraph) | `parent-error [parent-id parent]` returns an error map; it is not a `make-task` invariant. |
| Tree rules | § Component changes (`build-tree`) | Keep a root that is open or has an open child; closed root gets `:context? true`; hide closed children; progress counts all children. |
| Paging | § Data and storage, "Queries" | A page counts root families: `list-open` and `list-all` page the roots, then fetch all their children. |
| Progress | design.md § Display | Done over non-dropped; nil when all dropped. |
| Errors | § Shared contracts, "Error values" | Add `:task/not-found`, `:task/nesting-too-deep`, `:validation/bad-id`. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **4.1 `adapters.task/parse-id`**
  - Change: positive integer string to long, otherwise `:validation/bad-id`
    with the raw value in details.
  - Verify: `"12"`, `"abc"`, `"0"`, `"-1"`.
- [ ] **4.2 `parent-error` and `progress`**
  - Change: `parent-error` returns `:task/not-found` for a nil parent and
    `:task/nesting-too-deep` when the parent has a `:parent-id`. `progress`
    per the design.
  - Verify: both errors; progress with mixed statuses and with all dropped.
- [ ] **4.3 `build-tree` grouping**
  - Change: attach children to roots, ordered by the same sort rules; apply the
    open-mode filtering and `:context?` marking. `list-open` grows to select
    open roots and closed roots with an open child, and both list queries page
    by root and return every child of the page's roots.
  - Verify: open root with closed child, closed root with open child, closed
    root with only closed children (omitted), `:all?` showing everything, and a
    page boundary that never splits a parent from its children.
- [ ] **4.4 Display rows**
  - Change: `task->display-row` takes `extras` with progress (rendered `3/5`),
    `:dimmed?` for context roots, and `:parent-ref` (`↳ #12`).
    `render/tree-lines` indents children two spaces and dims context roots.
  - Verify: adapter tests for each extra.
- [ ] **4.5 `add --parent`**
  - Change: adapter parses `--parent` with `parse-id`; `add!` looks up the
    parent with `find-by-id` and returns `parent-error`'s result when non-nil.
  - Verify: integration tests below.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] `add x --parent 1` stores `parent_id = 1`; `list` shows it indented under
  task 1, which shows `0/1`.
- [ ] `--parent` pointing at a subtask exits 1 with the nesting message;
  pointing at a missing id exits 1 with `Task N not found.`; `--parent abc`
  exits 1 with the bad-id message.
- [ ] The tree is green at the boundary.

## Task tests

- **Unit:** `parse-id`, `parent-error`, `progress`, `build-tree` grouping and
  context rules, display-row extras.
- **End-to-end:** `add --parent` happy path and three failures; `list` output
  with a parent and two subtasks.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/logic/task.clj`, `src/gaoshun/adapters/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`, `src/gaoshun/diplomat/cli/render.clj`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

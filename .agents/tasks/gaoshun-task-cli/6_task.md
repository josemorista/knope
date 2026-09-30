---
name: edit-and-rm
overview: "Add `edit` with its flag validation and `rm` with the has-subtasks guard and single-statement `--force` cascade."
isProject: false
---

# Task 6.0: `edit` and `rm`

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `edit N` changes title, due date or note with validated flags
  and prints `Updated task N.`; `rm N` deletes, refusing parents with
  subtasks unless `--force`, which deletes the family in one statement.
- **Scope:** `args->changes`, `apply-edit`, `count-children`, `delete!`,
  `edit!`, `remove!`, two CLI commands.
- **Why this task:** completes the command set for managing tasks.
- **Ship group:** C (ships alone).
- **State at the boundary:** suite green.
- **Dependencies:** 5.0 (for `update!`, which `edit!` reuses).
- **Review focus:** both `rm` argument orders, which depend on the boolean
  coercion of `--force`.
- **Non-goals:** changing a task's parent or status through `edit`.

<skills>
## Standard skills compliance

- **`test-driven-development`:** Red-Green-Refactor per todo.
- **`diplomat-architecture`:** validation of flag combinations lives in the
  adapter; `apply-edit` is pure and rebuilds the task through `make-task`.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Edit flags | design.md § Commands | `--title`, `--due`, `--note`, `--clear-due`. |
| Errors | § Shared contracts, "Error values" | Add `:validation/nothing-to-edit`, `:validation/conflicting-flags`, `:task/has-subtasks`. |
| Cascade | § Component changes (`controllers/task.clj`) | Forced delete is `DELETE FROM tasks WHERE id = ? OR parent_id = ?`. |
| Boolean flags | § Component changes (`diplomat/cli.clj`) | `--force` and `--clear-due` coerced to `:boolean`. |
| Output | § Shared contracts, "Other command output" | `Updated task N.`, `Deleted task N.`, `Deleted task N and M subtasks.` |
| Missing id on `rm` | § Failure and operational behavior | `:task/not-found`, not a no-op. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **6.1 `args->changes`**
  - Change: build a changes map from the flags. No flags gives
    `:validation/nothing-to-edit`; `--due` with `--clear-due` gives
    `:validation/conflicting-flags`; blank `--title` gives
    `:validation/missing-title`; bad `--due` gives `:validation/bad-date`.
  - Verify: each error and a valid combination.
- [ ] **6.2 `logic.task/apply-edit`**
  - Change: merge changes into the task and rebuild through `make-task`.
  - Verify: title, due, clear-due and note changes.
- [ ] **6.3 `edit!`**
  - Change: find, `:task/not-found` if missing, `apply-edit`, `update!`. The FTS
    update trigger fires here when title or note change; its test belongs to 8.0.
  - Verify: integration tests.
- [ ] **6.4 `count-children`, `delete!`, `remove!`**
  - Change: `delete!` takes the id and a cascade flag, and runs one statement
    either way. `remove!` returns `:task/has-subtasks` with the count when
    children exist and not forced; otherwise deletes and returns the number of
    subtasks removed.
  - Verify: sqlite tests for plain delete and cascade.
- [ ] **6.5 CLI commands**
  - Change: dispatch entries for `edit` and `rm`, with `--force` and
    `--clear-due` coerced to boolean.
  - Verify: integration tests below.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] `edit N --title y` updates the title; `edit N --clear-due` nulls the due
  date.
- [ ] `edit N` alone and `edit N --due x --clear-due` exit 1 with the table's
  messages.
- [ ] `rm P` on a parent with two subtasks exits 1 with the has-subtasks
  message and deletes nothing.
- [ ] `rm P --force` and `rm --force P` both delete the parent and its
  subtasks and print `Deleted task P and 2 subtasks.`
- [ ] `rm 999` exits 1 with `Task 999 not found.`
- [ ] The tree is green at the boundary.

## Task tests

- **Unit:** `args->changes` errors; `apply-edit`.
- **Integration / contract:** `delete!` plain and cascade on a temp DB.
- **End-to-end:** the acceptance criteria above.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/logic/task.clj`, `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

---
feature: gaoshun-task-cli
reviewed: task 4.0, working tree vs HEAD (deec220) on feat/subtasks-and-transitions
diff-command: git diff HEAD -- src test
spec: .agents/tasks/gaoshun-task-cli/techspec.md
verdict: changes-requested (both Warnings resolved in the fix round)
findings: { blocker: 0, warning: 2, info: 2 }
axes: [spec, correctness, standards, alternatives]
axes-skipped: []
panel: [spec: sonnet, correctness: opus, standards: haiku, alternatives: sonnet]
---

# Review: task 4.0, subtasks

## Verdict

Subtasks, nesting errors, root-family paging and the nested `list` match the
spec, and the correctness pass found no defect. Two standards Warnings stand:
the controller trims the page's extra root itself, and the new adapter tests
check single keys instead of whole maps.

## Scope

- **Reviewed:** `src/gaoshun/adapters/task.clj`, `src/gaoshun/controllers/task.clj`,
  `src/gaoshun/diplomat/cli.clj`, `src/gaoshun/diplomat/cli/render.clj`,
  `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/logic/task.clj`, and the
  four matching test namespaces.
- **Set aside:** `.agents/tasks/gaoshun-task-cli/4_task.md` (spec artifact,
  checklist ticks only).

## Coverage

- **Axes run:**
  - spec: walked 4.1 to 4.5, the acceptance criteria and the cited techspec
    sections both ways; nothing missing, nothing unauthorized.
  - correctness: paging SQL (including NULL handling in the `IN` subquery),
    the 51st-root trim, `build-tree` filtering and progress, `add --parent`,
    and every caller of the changed signatures.
  - standards: diplomat-architecture checklist and testing rules, the task's
    compliance section.
  - alternatives: proportion, reuse, abstractions.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the standards reviewer read the test diff,
  not the full test files. The alternatives reviewer did not check `bb.edn` for
  a library that covers any of this.
- **Not covered by this gate:** the test suite. CI runs it. The main session
  ran `bb test` before review: 144 tests, 416 assertions, 0 failures. One
  test-writer expectation was corrected before review: page 1 of
  `list-does-not-leak-the-subtasks-of-the-next-page-root` now expects `0/1` on
  the parent, as the acceptance criteria require.

## Findings

### WARNING-1 — The controller trims the page's extra root
- **Axis:** standards
- **Location:** `src/gaoshun/controllers/task.clj:21-24`
- **Problem:** `list-tasks` finds the 51st root, removes it and its children,
  and derives `:more?`. That is a paging rule computed in the controller.
- **Evidence:** diplomat-architecture, Logic Sandwich: "Controllers orchestrate
  — they don't implement business rules or touch I/O directly."
- **Fix:** move the trim into a pure function in `logic/task.clj` that takes
  the fetched tasks and the page size and returns the shown tasks and
  `more?`, with a logic test; the controller calls it and passes the result to
  `build-tree`.
- **Verified:** read `controllers/task.clj:14-24`; the `extra`/`shown`
  computation is inline as described.
- **Status:** resolved. `logic.task/page-roots [tasks page-size]` returns
  `{:tasks :more?}`; the controller calls it. Three logic tests cover fewer
  than, exactly, and one past the limit with the extra root's child dropped.

### WARNING-2 — New adapter tests check single keys, not whole maps
- **Axis:** standards
- **Location:** `test/gaoshun/adapters/task_test.clj:150-187`
- **Problem:** `args->new-attrs-has-no-parent-by-default` checks only
  `:parent-id`; the four `task->display-row` extras tests check only
  `:progress`, `:dimmed?` or `:parent-ref`; `parse-id-reports-anything-else-*`
  and `args->new-attrs-reports-a-bad-parent-id` check the code and search the
  details values. An extra, missing or misnamed key passes.
- **Evidence:** diplomat-architecture, Testing Rules #18: "Whole-map
  assertions — Compare full output maps with `=`, not individual keys."
  Same rule as `reviews/3_review.md` WARNING-1.
- **Fix:** compare the whole result with `=`: the full attrs map, the full
  display row including the extra key, and
  `{:error/code :validation/bad-id :error/details {:input s}}`. Same cases.
- **Verified:** read `task_test.clj:145-187`; as described.
- **Status:** resolved. All seven tests compare whole maps with `=`, same
  cases. `bb test` green (147 tests, 409 assertions).

### INFO-1 — Usage text columns no longer line up
- **Axis:** none (noted by the main session)
- **Location:** `src/gaoshun/diplomat/cli.clj:13-14`
- **Problem:** the longer `add` synopsis pushes its description past the
  column the `list` line uses.
- **Fix:** pad the `list` line to the new column.
- **Verified:** light check, read the usage string.
- **Status:** open.

### INFO-2 — A colored due label ends the dim early on a context row
- **Axis:** none (noted by the implementer)
- **Location:** `src/gaoshun/diplomat/cli/render.clj` (`row-line`)
- **Problem:** the dim wraps the whole line, but the due label's own reset
  closes it, so the label prints in its urgency color, not dimmed. Context
  roots are closed, so with a past due date the label is red (see
  `reviews/3_review.md` INFO-1).
- **Fix:** product decision, alongside 3.0 INFO-1.
- **Verified:** light check, read `row-line`.
- **Status:** open.

## Requirements ledger

| Requirement (spec section) | Status | Evidence |
|---|---|---|
| 4.1 `parse-id`: positive integer to long, else `:validation/bad-id` with the raw value | met | `adapters/task.clj` `parse-id`; tests: WARNING-2 |
| 4.2 `parent-error [parent-id parent]`: `:task/not-found`, `:task/nesting-too-deep`; pure, not a `make-task` invariant | met | `logic/task.clj` `parent-error` |
| 4.2 `progress`: done over non-dropped, nil when all dropped | met | `logic/task.clj` `progress` |
| 4.3 `build-tree` keeps open roots and closed roots with an open child, marks `:context?`, hides closed children, progress counts all children | met | `logic/task.clj` `build-tree`, `tree-entry` |
| 4.3 Children use the same sort rules | met | `tree-entry` sorts with `compare-tasks` |
| Paging counts root families; `list-open` roots include closed roots with an open child; every child fetched | met | `diplomat/sqlite/task.clj` `page-with-children`; sqlite and integration paging tests |
| The 51st root is dropped with its children and only sets the "More" line | met | `controllers/task.clj` `list-tasks` (placement: WARNING-1) |
| Page `ORDER BY` agrees with `build-tree` | met | `list-queries-order-roots-like-build-tree` |
| 4.4 display-row extras: `3/5`, `:dimmed?`, `↳ #12` | met | `adapters/task.clj` `task->display-row` |
| 4.4 `tree-lines` indents children two spaces, dims context roots | met | `render.clj`; integration tests |
| 4.5 `--parent` parsed with `parse-id`; `add!` looks up the parent and returns `parent-error` | met | `adapters/task.clj`, `controllers/task.clj` |
| Messages for the three new codes match the techspec table | met | `diplomat/cli.clj` `error-message` |
| AC1: `add x --parent 1` stores the parent; `list` nests it, parent shows `0/1` | met | integration tests |
| AC2: subtask parent, missing parent, `--parent abc` exit 1 with the right message | met | integration tests |
| AC3: green at the boundary | met | `bb test` run by the main session |
| Non-goal: `:parent-ref` not rendered | met | `render.clj` ignores it |
| Scope: nothing in the diff lacks a requirement | met | spec reviewer's backward walk |

## Discarded

None.

## Verified clean

The correctness pass traced root-family paging (including the exactly-50-roots
edge and the empty page), `build-tree` filtering and progress, `add --parent`
end to end, and every caller of the changed signatures, and found nothing. The
new integration tests pin the page-boundary cases with literal expected
output. Layer rules hold: `parent-error` is pure, the parent lookup is in the
controller, and the CLI diplomat requires no sqlite namespace.

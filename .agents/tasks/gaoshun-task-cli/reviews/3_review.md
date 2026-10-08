---
feature: gaoshun-task-cli
reviewed: task 3.0, working tree vs HEAD (898fc3b) on feat/add-and-list
diff-command: git diff HEAD -- src test bin && git ls-files --others --exclude-standard -- src test bin
spec: .agents/tasks/gaoshun-task-cli/techspec.md
verdict: changes-requested (Warning resolved in the fix round)
findings: { blocker: 0, warning: 1, info: 3 }
axes: [spec, correctness, standards, alternatives]
axes-skipped: []
panel: [spec: sonnet, correctness: opus, standards: haiku, alternatives: sonnet]
---

# Review: task 3.0, flat `list` and the wrapper

## Verdict

`list`, `list --all`, rendering and the wrapper match the spec. One Warning:
the `task->display-row` tests check a set of values instead of the whole row
map, which the project's testing rule forbids.

## Scope

- **Reviewed:** `src/gaoshun/logic/date.clj`, `src/gaoshun/logic/task.clj`,
  `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`,
  `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`,
  `src/gaoshun/diplomat/cli/render.clj` (new), `bin/gaoshun` (new), and the
  extended tests under `test/gaoshun/`.
- **Set aside:** `.clj-kondo/`, `.lsp/` (editor caches); `.agents/**` (spec
  artifacts). Outside the repo: the `~/.local/bin/gaoshun` symlink the task asks
  for.

## Coverage

- **Axes run:**
  - spec: walked task 3.0 and the techspec both ways; no missing work, nothing
    unauthorized.
  - correctness: sort comparator, `due-label` boundaries, render callers, and
    the wrapper script.
  - standards: diplomat-architecture layer map, TDD, whole-map assertions.
  - alternatives: proportion and reuse.
- **Axes skipped:** none.
- **Coverage gaps within an axis:** the spec reviewer read test names, not
  bodies. No automated test runs `bin/gaoshun` itself; the subprocess test calls
  `bb --config` directly, so the wrapper's TTY branch rests on the
  implementer's manual pty check. Nobody has viewed the colors in an
  interactive terminal.
- **Not covered by this gate:** the test suite. CI runs it.

## Findings

### WARNING-1 — Display-row tests compare value sets, not the whole map
- **Axis:** standards
- **Location:** `test/gaoshun/adapters/task_test.clj:107-139`
- **Problem:** the `row-values` helper turns the row into `(set (vals row))`
  and the four `task->display-row` tests check membership. Extra keys, missing
  keys, or a value under the wrong key would pass.
- **Evidence:** diplomat-architecture, Testing Rules #18: "Whole-map
  assertions — Compare full output maps with `=`, not individual keys."
  Techspec § Test behaviors: "Assertions compare whole maps with `=`." The
  helper's docstring says it avoided key names because the spec didn't name
  them; the implementation has since fixed them as
  `{:glyph :id :title :due-label :urgency}`.
- **Fix:** rewrite the four tests as whole-map `=` against
  `{:glyph :id :title :due-label :urgency}`, keeping the same cases, and drop
  `row-values`.
- **Verified:** read `task_test.clj:107-139`; as described.
- **Status:** resolved. The four tests now compare the whole row with `=` and
  `row-values` is gone; same cases, no production change, `bb test` green
  (88 tests, 227 assertions).

### INFO-1 — Closed tasks under `list --all` show a red "Nd overdue"
- **Axis:** correctness
- **Location:** `src/gaoshun/adapters/task.clj:56-63`, `src/gaoshun/logic/date.clj:24-33`
- **Problem:** `task->display-row` labels any past due date as overdue
  regardless of status, so a task finished before its due date still renders
  red. Matches the written contract (§ Relative due label), which doesn't
  consider closed tasks. Reachable in normal use once 5.0 adds `done`.
- **Fix:** product decision: drop or dim the label for `:done`/`:dropped`
  (possibly through 4.0's `:dimmed?`), plus one adapter test.
- **Verified:** light check; the implementer raised the same question.
- **Status:** open.

### INFO-2 — Acceptance boxes unchecked
- **Axis:** spec-adherence
- **Location:** `.agents/tasks/gaoshun-task-cli/3_task.md:81-85`
- **Fix:** tick the four the run verified; leave the manual terminal check for
  the developer.
- **Status:** resolved at record time (the interactive-terminal color check
  stays unticked).

### INFO-3 — `task->display-row` takes an unused `extras` argument
- **Axis:** alternatives (raised and set aside by the reviewer)
- **Location:** `src/gaoshun/adapters/task.clj`
- **Problem:** every caller passes `{}`. It is the techspec signature, kept for
  4.0's progress and parent reference.
- **Fix:** none now.
- **Status:** open.

## Requirements ledger

| Requirement (spec section) | Status | Evidence |
|---|---|---|
| `due-label [due today]` returns `{:text :urgency}` | met | `logic/date.clj` `due-label` |
| `today`/`tomorrow` `:soon`; `in Nd` 2-7 `:later` | met | `logic/date.clj` |
| ISO beyond 7 days; `Nd overdue` `:overdue` | met | `logic/date.clj` |
| nil due date → nil | met | `logic/date.clj` |
| Boundary tests -1, 0, 1, 2, 7, 8 | met | `due-label-*` tests |
| `build-tree` open only; `:all?` keeps all | met | `logic/task.clj` |
| Sort: open before closed; due asc, nil last, id | met | `compare-tasks` |
| Sort: closed by finish desc, id desc | met | `compare-tasks` |
| Entries extensible for `:children`/`:context?` | met | `{:task t}` entries |
| `task->display-row` with four glyphs, id, title, label, urgency | met | `adapters/task.clj`; tests: WARNING-1 |
| `tree-lines` with red/yellow/dim and `Nothing pending.` | met | `render.clj` |
| ANSI only when `:color?` | met | `render.clj` `colorize` |
| `list-all` is `SELECT * FROM tasks` | met | `diplomat/sqlite/task.clj` |
| `list-tasks [all? components]` returns the tree | met | `controllers/task.clj` |
| `list` with `--all` coerced to boolean | met | `diplomat/cli.clj` |
| `-main` `:color?` from `GAOSHUN_TTY`/`NO_COLOR` | met | `diplomat/cli.clj` |
| `bin/gaoshun` resolves symlink, sets `GAOSHUN_TTY` 1/0, `bb --config` | met | `bin/gaoshun` |
| Wrapper executable and symlinked into `~/.local/bin` | met | `ls -l` |
| Empty DB prints `Nothing pending.`, exit 0 | met | integration test |
| `:color? false` has no escapes | met | integration test |
| `list --all` with directly inserted closed rows | met | integration test |
| Manual: `/tmp`, color in a terminal, none with `\| cat`/`NO_COLOR` | partial | pty and pipe checks by the implementer; interactive viewing not done |
| Subtasks, progress, context parents out of scope | met | none in the diff |

## Discarded

None — every finding survived verification.

## Verified clean

The sort comparator and `due-label` boundaries are pinned by tests that would
fail on off-by-one or order changes, the layer rules hold, and the wrapper
always overrides an exported `GAOSHUN_TTY`.

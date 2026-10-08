# gaoshun task CLI: implementation tasks

## Reviewer guide

- **Cut chosen:** vertical slices by command, following the techspec's
  "Delivery increments", with increment 2 (`add` + `list`) split three ways
  into `add`, flat `list` with the wrapper, and subtasks. Increment 2 as written
  touches every layer and roughly fifteen functions, which is more than one
  agent can hold or one reviewer can read in a sitting. The strongest
  alternative was the layer cut the plan review already rejected (all logic,
  then all adapters, then all SQL): it leaves nothing runnable until late and
  every task is hard to verify on its own.
- **Sequence:** 1.0 proves the runtime before any feature code, because a
  failure there changes the design. 2.0 to 4.0 build up to a usable tracker.
  5.0 adds status changes, which 6.0, 7.0 and 8.0 all need to create finished
  tasks in their tests.
- **Parallel frontier:** 1.0 to 5.0 are a strict chain. After 5.0, tasks 6.0,
  7.0 and 8.0 are independent and can run in parallel.
- **Judgment calls:**
  - Techspec increment 2 is split into 2.0, 3.0 and 4.0 (reason above).
  - `parse-id` lands in 4.0, the first task that reads an id from argv
    (`--parent`). Transitions, `edit` and `rm` reuse it.
  - The `:parent-ref` field on display rows (`↳ #12`) lands in 4.0 with the
    rest of the subtask display work, even though only `day` (7.0) and `search`
    (8.0) render it. That keeps 7.0 and 8.0 from both needing to add it.
  - The error message table in `diplomat.cli` grows task by task: each task
    adds the codes its commands can return. The techspec's table under
    "Shared contracts" is the target.
  - babashka v1.13.225 was installed by the user before the run, so 1.1 only
    verifies it. Models are the latest of each family (Opus 5.5, Sonnet 5.5).
  - The repo was initialized on `main` before the run (signed root commit
    `01b76c1`). Each ship group is a branch stacked on the previous one. The
    private remote `josemorista/gaoshun` was added after group A.
- **Review focus:** the size of 2.0 and 3.0, which are the largest tasks; and
  the 5.0 → 6.0/7.0/8.0 edges, which exist only because those tasks need
  finished tasks and `update!` in their tests.
- **Shipping:** greenfield, so no merge can break existing behavior. Each group
  ends with `bb test` green and every command it adds working through
  `bin/gaoshun`.
- **Blockers:** None.

## Tasks

Each task is one focused implementation pass: reviewable on its own and green
when it finishes. Ship groups say which tasks merge together.

### Ship group A: a usable `add` and `list`

**Branch:** `feat/add-and-list` · **Base:** `main` · **PR:** [#1](https://github.com/josemorista/gaoshun/pull/1) (draft)

**Merge safety:** new project. When the group lands, `gaoshun add` and
`gaoshun list` work end to end through the wrapper.

- [x] **1.0 Runtime check and schema** — babashka and the pod run, the schema
  including FTS is created and tested, and every runtime risk has a recorded
  result. **Blocked by:** None.
  **Model:** sonnet · **Status:** committed · **Commit:** `42dae3d` · **Review:** `reviews/1_review.md` (2 open)
- [x] **2.0 `add` end to end** — `run` dispatches `add`, which validates input,
  inserts a task and prints `Added task N.`; bad input exits 1 with the spec's
  messages. **Blocked by:** 1.0.
  **Model:** opus · **Status:** committed · **Commit:** `898fc3b` · **Review:** `reviews/2_review.md` (5 open, incl. 1 Warning)
- [x] **3.0 Flat `list` and the wrapper** — `gaoshun list` shows open tasks
  sorted by due date with colored relative labels, through `bin/gaoshun` from
  any directory. **Blocked by:** 2.0.
  **Model:** opus · **Status:** committed · **Commit:** `b4d60ce` · **Review:** `reviews/3_review.md` (3 open, Info only)

### Ship group B: subtasks and status changes

**Branch:** `feat/subtasks-and-transitions` · **Base:** `feat/add-and-list` · **PR:** none (local only)

**Merge safety:** additive commands and flags only; group A behavior is
unchanged except that `list` now nests subtasks.

- [ ] **4.0 Subtasks** — `add --parent` creates a subtask, nesting rules are
  enforced, and `list` renders subtasks indented with progress. **Blocked by:** 3.0.
  **Model:** sonnet · **Status:** pending · **Commit:** — · **Review:** —
- [ ] **5.0 Status transitions** — `start`, `done`, `drop` and `reopen` follow
  the transition table, idempotently, and `list` shows the dimmed parent
  header. **Blocked by:** 4.0.
  **Model:** sonnet · **Status:** pending · **Commit:** — · **Review:** —

### Ship group C: editing and deletion

**Branch:** `feat/edit-and-rm` · **Base:** `feat/subtasks-and-transitions` · **PR:** none (local only)

**Merge safety:** additive commands only.

- [ ] **6.0 `edit` and `rm`** — tasks can be edited with the validated flags
  and deleted, with `--force` cascading to subtasks in either argument order.
  **Blocked by:** 5.0.
  **Model:** sonnet · **Status:** pending · **Commit:** — · **Review:** —

### Ship group D: history and search

**Branch:** `feat/day-and-search` · **Base:** `feat/edit-and-rm` · **PR:** none (local only)

**Merge safety:** additive, read-only commands.

- [ ] **7.0 `day` view** — `gaoshun day [DATE]` prints the Done, Dropped and
  Open sections with the midnight boundary handled and index use confirmed.
  **Blocked by:** 5.0.
  **Model:** sonnet · **Status:** pending · **Commit:** — · **Review:** —
- [ ] **8.0 Full-text `search`** — `gaoshun search TEXT` finds tasks by word
  prefix through FTS5, stays in sync on insert, update and delete, and rejects
  punctuation-only input. **Blocked by:** 5.0.
  **Model:** sonnet · **Status:** pending · **Commit:** — · **Review:** —

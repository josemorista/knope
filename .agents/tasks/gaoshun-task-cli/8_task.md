---
name: full-text-search
overview: "Add `gaoshun search TEXT` over the FTS5 index: escaped prefix matching, relevance order, index sync verified on insert, update and delete."
isProject: false
---

# Task 8.0: Full-text `search`

<critical>Read `techspec.md` in this feature folder before starting, especially the sections cited below.</critical>

## Reviewer guide

- **Outcome:** `gaoshun search auth serv` lists tasks whose title or note has
  words starting with `auth` and `serv`, most relevant first; `search -`
  exits 1 with `Search needs at least one word.`
- **Scope:** `search-match`, SQLite `search`, FTS sync tests,
  `render/flat-lines`, the `search` controller and command.
- **Why this task:** delivers "where is that task I wrote down".
- **Ship group:** D, with 7.0.
- **State at the boundary:** suite green; every command in the design exists.
- **Dependencies:** 5.0 (`update!`, used to test the update trigger).
- **Review focus:** `search-match` escaping; this is the only user text that
  reaches SQL as syntax rather than as a bound value.
- **Non-goals:** substring matching, search filters by status.

<skills>
## Standard skills compliance

- **`test-driven-development`:** adapter and FTS sync tests before
  implementation.
- **`diplomat-architecture`:** escaping is a translation and lives in the
  adapter; the diplomat binds the result with `?`.
</skills>

## Tech spec alignment

| Requirement or decision | Tech spec source | Implication for this task |
|---|---|---|
| Match expression | § Data and storage, "Queries" (last paragraph) | Split on whitespace, drop words without letters or digits, double `"`, join `"word"*`. |
| Query | § Data and storage, "Queries" | Use the `search` statement verbatim, ordered by `bm25`. |
| Triggers | § Data and storage, "Schema" | Already created in 1.0; this task tests them. |
| Errors | § Shared contracts, "Error values" | Add `:validation/empty-search`. |
| Output | § Shared contracts, "Other command output" | Flat lines in relevance order; `No matches.` when empty. |

## Todos

<progress>This checklist is the live implementation tracker. Check each item as it is completed; the nested bullets carry the execution detail.</progress>

- [ ] **8.1 `adapters.task/search-match`**
  - Change: per the contract; `:validation/empty-search` when no word remains.
  - Verify: `auth serv` → `"auth"* "serv"*`; `say "hi"` keeps `say` and
    escapes the quote in `"hi"`; `- . "` → error; accented input passes
    through unchanged.
- [ ] **8.2 SQLite `search` and sync tests**
  - Change: the `search` query with one positional argument.
  - Verify against a temp DB: found after insert; after `update!` of the title
    the new word matches and the old one doesn't; not found after `delete!`;
    an `update!` that changes only status leaves results unchanged;
    `auth.svc` built through `search-match` runs without an FTS error;
    `autenticação` is found by `autenticacao`.
- [ ] **8.3 Controller, render and command**
  - Change: `controllers.task/search [match components]`; `flat-lines` with
    `:parent-ref`; CLI `search TEXT` joining all positional words.
  - Verify: integration tests below.
- [ ] **Tests**
  - Write and run the task-level tests listed below; confirm failures are caused by missing behavior before implementation.
- [ ] **Validate**
  - Run `bb test`; resolve regressions before completion.

## Acceptance criteria

- [ ] Prefix matching works (`auth` finds "Authentication").
- [ ] Results are ordered by relevance.
- [ ] Subtask results show `↳ #N`.
- [ ] Punctuation-only input exits 1 with the empty-search message and never
  reaches SQLite.
- [ ] No matches prints `No matches.` and exits 0.
- [ ] The tree is green at the boundary.

## Task tests

- **Unit:** `search-match` cases.
- **Integration / contract:** FTS sync and accent folding on a temp DB.
- **End-to-end:** a match, a subtask match, no matches, punctuation-only input.

## Open questions

None.

## Relevant files

- **Modify:** `src/gaoshun/adapters/task.clj`, `src/gaoshun/diplomat/sqlite/task.clj`, `src/gaoshun/controllers/task.clj`, `src/gaoshun/diplomat/cli.clj`, `src/gaoshun/diplomat/cli/render.clj`
- **Modify:** matching test namespaces and `test/gaoshun/integration/cli_test.clj`

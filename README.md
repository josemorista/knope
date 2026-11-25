# knope

A small task list for the command line, named for Leslie Knope, who would have a binder for every task and a plan for every binder. knope keeps your tasks, subtasks and due dates in a local SQLite file and answers in her voice when things go well.

## Requirements

- [babashka](https://github.com/babashka/babashka) 1.13.229 or later.
- Network access on the first run. babashka downloads the SQLite pod once and caches it. Without the network and without the cached pod, the first command fails inside babashka before knope starts, and nothing is written.

## Install

Download the jar from the latest release:

```sh
mkdir -p ~/.local/share/knope ~/.local/bin
curl -fL -o ~/.local/share/knope/knope.jar \
  https://github.com/josemorista/knope/releases/latest/download/knope.jar
```

Create a launcher at `~/.local/bin/knope`:

```sh
#!/usr/bin/env bash
exec bb "$HOME/.local/share/knope/knope.jar" "$@"
```

Make it executable with `chmod +x ~/.local/bin/knope`, and make sure `~/.local/bin` is on your `PATH`.

## Usage

```
knope tasks add <title> [--due DATE] [--note TEXT] [--parent ID]
knope tasks list [--all] [--page N]
knope tasks view ID
knope tasks start ID
knope tasks done ID
knope tasks drop ID
knope tasks reopen ID
knope tasks edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]
knope tasks rm ID [--force]
knope tasks day [DATE]
knope tasks search TEXT
knope t <command>
knope remote set <git-url>
knope push [--force]
knope pull [--force]
knope update
knope completion bash|zsh
knope --version
```

`t` is short for `tasks`: `knope t add "Buy stamps"` works like `knope tasks add "Buy stamps"`.

A task can have subtasks, one level deep. `tasks list` shows pending tasks, 50 per page, and `--all` includes closed ones. `tasks view` shows one task in full, with its note, parent and subtasks. `tasks rm` refuses to delete a task that has subtasks unless you pass `--force`. `tasks search` finds tasks whose title or note has every word as a prefix. `tasks day` shows what was done, dropped and open on a day, today by default, with subtasks under their parent. A parent that is not itself on that day appears above its subtask, dimmed when color is on.

Dates are written as `YYYY-MM-DD`, `today`, `tomorrow`, `yesterday`, `+Nd` or `-Nd`.

An example session:

```
$ knope tasks add "Plan the harvest festival" --due +3d
Task 1 is on the list! I've already started a binder for it.
$ knope tasks add "Book the venue" --parent 1
Task 2 is on the list! I've already started a binder for it.
$ knope tasks start 2
Task 2 is in progress. Let's make Pawnee proud!
$ knope t list
Here's the agenda, let's get to work:

[ ] 1 Plan the harvest festival  0/1  in 3d
  [~] 2 Book the venue

Now go make Pawnee proud.
$ knope t done 2
Task 2 is DONE! This calls for waffles.
```

## Completion

knope completes commands, flags and the IDs of open tasks in bash and zsh. Add one line to your shell's startup file:

```sh
# zsh, after compinit
source <(knope completion zsh)

# bash
source <(knope completion bash)
```

Completing a command or flag never touches your database; completing a task ID reads it.

## Data and colors

Tasks live in `~/.knope/tasks.db`. Set `KNOPE_DB` to the path of another file to use it instead.

Colors show up when the output is a terminal. Set `NO_COLOR` to any value to turn them off. Piped output never has colors.

## Sync

knope can keep your tasks in step across machines through a git remote, a GitHub gist by default. Sync needs git installed; every other command works without it.

Create a secret gist at [gist.github.com](https://gist.github.com) with any placeholder file (a gist can't be empty), then point knope at its git URL:

```
knope remote set git@gist.github.com:<gist-id>.git
knope push
knope pull
```

`push` uploads your database as one file, `tasks.knope`, and leaves the gist's other files alone. `pull` replaces your database with the gist's copy. Each command refuses when it would overwrite changes it hasn't seen: `push` when the gist changed since this machine last synced, `pull` when this database has unpushed changes, or on a first pull over a database that already has tasks. Pass `--force` to choose your side anyway. A forced push adds a new gist revision, so earlier ones stay in the gist's history. A forced pull keeps the replaced database at `<db file>.sync/before-pull.db`.

Encryption is mandatory. knope asks for a passphrase on every push or pull that has work to do (twice on the first push) and encrypts the file with AES-256-GCM before it leaves your machine. The passphrase is never stored. If you forget it, the remote copy can't be recovered; your local database is unaffected. Sync needs a terminal to read the passphrase.

knope stores no credentials. GitHub access is whatever your git already has: an SSH key for `git@` URLs, or `gh auth setup-git` for HTTPS URLs. With SSH, git asks for the key's passphrase on every git call knope makes unless the key is loaded in ssh-agent (`ssh-add`). HTTPS works without prompts after `gh auth setup-git`.

Sync state for each database lives next to it, in a `<db file>.sync/` folder such as `~/.knope/tasks.db.sync/`.

## Platforms

knope runs on Linux and macOS. On Apple Silicon, babashka runs the SQLite pod under Rosetta 2. Windows is untested.

## Updating

Run `knope update` from an installed jar. It downloads the latest release and replaces the jar in place, keeping its permissions. Your tasks stay where they are.

A jar too old to have `update` is updated by hand: download the jar again to the same path.

## Development

Run the tests with `bb test`. Run from a checkout with `bin/knope`. Build the jar with `bb build`, which writes `target/knope.jar`.

To cut a release, run the `bump` workflow from the repository's default branch in GitHub Actions and pick `major`, `minor` or `patch`. It takes the highest `vMAJOR.MINOR.PATCH` tag (or `0.0.0` when there is none), bumps it, runs the tests, builds the jar with that version, checks that the jar prints it, and publishes a `vMAJOR.MINOR.PATCH` release with `knope.jar` attached. If the tests or the version check fail, no tag or release is published. Run from any other branch, the workflow fails before doing anything.

## License

MIT. See [LICENSE](LICENSE).

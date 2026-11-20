# knope

A small task list for the command line, named for Leslie Knope, who would have a binder for every task and a plan for every binder. knope keeps your tasks, subtasks and due dates in a local SQLite file and answers in her voice when things go well.

## Requirements

- [babashka](https://github.com/babashka/babashka) 1.12.215 or later.
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
knope add <title> [--due DATE] [--note TEXT] [--parent ID]
knope list [--all] [--page N]
knope start ID
knope done ID
knope drop ID
knope reopen ID
knope edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]
knope rm ID [--force]
knope day [DATE]
knope search TEXT
```

A task can have subtasks, one level deep. `list` shows pending tasks, 50 per page, and `--all` includes closed ones. `rm` refuses to delete a task that has subtasks unless you pass `--force`. `search` finds tasks whose title or note has every word as a prefix. `day` shows what was done, dropped and open on a day, today by default.

Dates are written as `YYYY-MM-DD`, `today`, `tomorrow`, `yesterday`, `+Nd` or `-Nd`.

An example session:

```
$ knope add "Plan the harvest festival" --due +3d
Task 1 is on the list! I've already started a binder for it.
$ knope add "Book the venue" --parent 1
Task 2 is on the list! I've already started a binder for it.
$ knope start 2
Task 2 is in progress. Let's make Pawnee proud!
$ knope list
[ ] 1 Plan the harvest festival  0/1  in 3d
  [~] 2 Book the venue
$ knope done 2
Task 2 is DONE! This calls for waffles.
```

## Data and colors

Tasks live in `~/.knope/tasks.db`. Set `KNOPE_DB` to the path of another file to use it instead.

Colors show up when the output is a terminal. Set `NO_COLOR` to any value to turn them off. Piped output never has colors.

## Platforms

knope runs on Linux and macOS. On Apple Silicon, babashka runs the SQLite pod under Rosetta 2. Windows is untested.

## Updating

Download the jar again to the same path. Your tasks stay where they are.

## Development

Run the tests with `bb test`. Run from a checkout with `bin/knope`. Build the jar with `bb build`, which writes `target/knope.jar`.

To cut a release, push a tag named `vMAJOR.MINOR.PATCH`, for example `v0.1.0`. A GitHub Actions workflow runs the tests, builds the jar and publishes a release with `knope.jar` attached. If the tests fail, no release is published.

## License

MIT. See [LICENSE](LICENSE).

# Contributing

Thank you for your interest in this project.

## Rules

### Do not read copyleft source code for this work

Do not read the source code of projects under a copyleft license when you work on this project.
Copyleft licenses include GPL, AGPL, and LGPL.

You may read their public documentation to learn concepts and data models.
You may read the source code of projects under a permissive license, such as Apache 2.0, MIT, and BSD.
Keycloak is under Apache 2.0, so you may read its source code.

Check the license of a project before you open its source code.

### Write plain English

Write code, comments, documents, issues, and pull requests in plain English.
Use short sentences.

### Make every change through a pull request

Do not push to `main`.
Open a pull request and wait for the `CI result` check to pass.

### Keep comments short

Do not explain in a comment what the code already says.
Write a comment only for something the code cannot say, such as a reason.

## Labels

| Label | Use |
|---|---|
| `feature` | New feature |
| `bug` | Something does not work |
| `chore` | Build, CI, docs, and other upkeep |

## Build and test

```sh
./gradlew check
```

## Quality scan

CI also runs [aislop](https://github.com/scanaislop/aislop), a scanner for common code problems. Run the same gate before you open a pull request.

```sh
scripts/aislop.sh
```

The script needs Docker. aislop is a third-party tool from npm, so the script never runs it on your machine.

- The tool runs in a container with no network and no privileges.
- The container gets a read-only copy of the tracked files. It cannot change the repository.
- Only the step that downloads the tool has the network, and that step sees no project file.
- Telemetry is off.

The script scans tracked files with their changes that are not committed yet. Run `git add` on a new file first.

The rules are in `.aislop/config.yml`. The gate fails on any finding of the level error, and on a score below 90.
When a finding is wrong, add `aislop-ignore-next-line <rule> -- <reason>` in a comment on the line above it.

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
Open a pull request and wait for the `Test gate` and `Lint gate` checks to pass.

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

`check` also writes one coverage report for the unit tests and the integration tests, in `build/reports/jacoco/test`.
The provider runs in the JVM of Keycloak, so the integration tests put the JaCoCo agent into their Keycloak containers and read its data out.

`check` fails when the tests run less than 99% of the lines. A line that no test runs is a case that cannot happen, or dead code.
Write a test for it, or remove it. The domain package must be covered in full by unit tests alone.

The compiler runs with every warning turned on, and with [Error Prone](https://errorprone.info/).
A warning fails the build. Fix the cause. When a warning is wrong, use `@SuppressWarnings` with the name of the check, and say why in a comment.

## Quality scan

CI also runs [aislop](https://github.com/scanaislop/aislop), a scanner for common code problems. Run the same gate before you open a pull request.

```sh
scripts/aislop.sh
```

The script needs Docker. aislop is a third-party tool from npm, so the script never runs it on your machine.

- The tool runs in a container with no network and no privileges.
- The container gets a read-only copy of the tracked files. It cannot change the repository.
- Only the build of the image has the network, and that build sees no project file. The image is built once and then reused.
- Telemetry is off.

The script scans tracked files with their changes that are not committed yet. Run `git add` on a new file first.

The rules are in `.aislop/config.yml`. The gate fails on any finding of the level error, and on a score below 90.
When a finding is wrong, add `aislop-ignore-next-line <rule> -- <reason>` in a comment on the line above it.

## CodeQL

CI also runs CodeQL, the code scanning of GitHub, on the Java code and on the workflows.
It follows a value from a request to the place where it is used, so it finds injection into a query even through several methods.
The results are in the "Security" tab of the repository. A pull request shows new findings as a check.

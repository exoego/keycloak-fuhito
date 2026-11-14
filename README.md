# keycloak-fuhito

[![codecov](https://codecov.io/gh/exoego/keycloak-fuhito/graph/badge.svg?token=7xvNfUdlJX)](https://codecov.io/gh/exoego/keycloak-fuhito)

A Keycloak extension that stores organization structure and its history for each realm.

It answers questions like these.

- Which org units exist, and how do they form a tree?
- Who works in which org unit, with which job title and occupation?
- What was the answer on a given date in the past, or what will it be on a future date?
- Who approved a change, and when did it take effect?

The name comes from "fuhito", an old Japanese word for a scribe who keeps records.

## Status

This project has no release yet, and its API can still change.

What works today:

- Org units with a tree, dated changes, moves, and ends.
- Employments with terms that change over time, such as employment type, grade, leave, and work percentage.
- Assignments of a person to any number of org units at once, with job title, occupation, and a primary flag.
- Reporting lines, and managers derived from the heads of org units.
- Qualifications of users.
- Reads of the state on any date, and of the full history.
- Change orders with an approver, and a change log of every write.
- Reorganizations of many org units in one transaction.
- Bulk import by JSON and CSV with a dry run, and a full export.
- A protocol mapper that adds current assignments to tokens.
- An optional projection into the groups of Keycloak organizations, kept up to date by a timer.
- A sync from user attributes that an identity provider fills over SCIM.
- Messages in English and Japanese.

The [open issues](https://github.com/exoego/keycloak-fuhito/issues) show what is planned next.

## Scope

One realm is one legal entity.
A realm can hold several top-level org units, for example several hospitals of one corporation.
Each of them has its own tree of org units.

Access control is out of scope. The caller decides who may do what with the data.

[docs/concepts.md](docs/concepts.md) defines the terms.
[docs/history.md](docs/history.md) explains how dated changes and the change log behave.
[docs/import.md](docs/import.md) explains bulk import by JSON and CSV, and the export.
[docs/scim.md](docs/scim.md) explains how data from SCIM reaches the org structure.
[docs/personal-data.md](docs/personal-data.md) explains what is stored about a person, and how to keep, mask, or erase it.
[docs/keycloak-integration.md](docs/keycloak-integration.md) explains how the extension plugs into Keycloak.

## Compatibility

| Item | Version |
|---|---|
| Keycloak | 26.8 or later |
| Java | 21 or later |
| Database | PostgreSQL |

## Set up a realm

A realm must say where it is before it uses the API. Set the realm attribute `fuhito.timeZone`, for example to `Asia/Tokyo`.
The extension never uses the time zone of the server to decide what today is.
An org unit in another time zone can set its own, for example a branch in another country.
See [docs/keycloak-integration.md](docs/keycloak-integration.md).

## Build

```sh
./gradlew build
```

The build needs JDK 21.

## Test

```sh
./gradlew check
```

The integration tests start Keycloak and PostgreSQL in containers, so they need Docker.
To test another Keycloak version, pass `-PtestKeycloakVersion=26.8.0`.

## Release

Publish a GitHub release with a tag like `v1.0.0`.
The `Publish to Maven Central` workflow takes the version from the tag, runs the tests, and publishes the jar.

The workflow needs a `release` environment in the repository settings with these secrets.

- `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`
- `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)

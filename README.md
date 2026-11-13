# keycloak-fuhito

A Keycloak extension that stores organization structure and its history for each realm.

It answers questions like these.

- Which org units exist, and how do they form a tree?
- Who works in which org unit, with which job title and occupation?
- What was the answer on a given date in the past, or what will it be on a future date?
- Who approved a change, and when did it take effect?

The name comes from "fuhito", an old Japanese word for a scribe who keeps records.

## Status

This project is at an early stage. It has no release yet, and nothing is stable.
The [milestones](https://github.com/exoego/keycloak-fuhito/milestones) show the plan.

## Scope

One realm is one legal entity.
A realm can hold several top-level org units, for example several hospitals of one corporation.
Each of them has its own tree of org units.

Access control is out of scope. The caller decides who may do what with the data.

[docs/concepts.md](docs/concepts.md) defines the terms.
[docs/keycloak-integration.md](docs/keycloak-integration.md) explains how the extension plugs into Keycloak.

## Compatibility

| Item | Version |
|---|---|
| Keycloak | 26.8 or later |
| Java | 21 or later |
| Database | PostgreSQL |

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

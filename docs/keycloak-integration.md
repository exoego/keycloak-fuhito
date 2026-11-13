# Keycloak integration

This document explains how the extension plugs into Keycloak.
The integration tests check the tables, the entities, and the admin REST API against Keycloak 26.8.
The notes on `kc.sh` come from the Keycloak documentation.

## Unsupported Keycloak APIs

The extension uses three Keycloak APIs that Keycloak does not promise to keep stable.

| API | Use |
|---|---|
| `JpaEntityProvider` | Own tables in the Keycloak database |
| `AdminRealmResourceProvider` | Own endpoints in the admin REST API |
| `AdminPermissionEvaluator` | Permission checks for the caller |

All code that touches these APIs is in the package `net.exoego.keycloak.fuhito.keycloak`.
When Keycloak changes one of them, only that package needs a change.

## Tables

- Every table name starts with `FUHITO_`.
- No table has a foreign key to a Keycloak table. Rows store Keycloak IDs as plain values.
- Liquibase creates and updates the tables when Keycloak starts.
- The changelog is `META-INF/fuhito/changelog.xml` in the jar.

## Entities

Keycloak finds the JPA entities of the jar when it builds the server.
The extension does not list them in `JpaEntityProvider.getEntities()`.
Keycloak ignores that method and plans to remove it.

For this reason the server must be built again after the jar is added or updated.

- `kc.sh start-dev` and `kc.sh start` build the server when needed.
- `kc.sh start --optimized` does not. Run `kc.sh build` first.

## Admin REST API

The endpoints are under `/admin/realms/{realm}/fuhito`.
The caller needs an access token for the admin API, the same as for the other admin endpoints.

| Operation | Required permission |
|---|---|
| Read | View users |
| Write | Manage users |

A request with no token gets 401.
A caller without the permission gets 403.

A response leaves out every field that has no value.
For example, an org unit with no end date has no `validTo` field.

## Dates

Dates have the form `YYYY-MM-DD`.
A period includes its `validFrom` date and does not include its `validTo` date.

Read endpoints take an `asOf` date.
The default is today in the time zone of the server.
Clients in another time zone should always send `asOf`.

## Errors

An error response has a stable code, the values that belong to it, and a message for people.

```json
{
  "error": "ref_item.code_taken",
  "params": {"kind": "UNIT_TYPE", "code": "hospital"},
  "message": "The code \"hospital\" is already used for the kind UNIT_TYPE."
}
```

Clients should rely on `error` and `params`. The text of `message` can change.
The language of `message` follows the `Accept-Language` header of the request.
English and Japanese exist. Any other language gets English.
To add a language, add a `messages_xx.properties` file next to the others.
A failed request writes nothing.

| Status | Meaning |
|---|---|
| 400 | A value in the request is bad |
| 404 | The target does not exist in the realm |
| 409 | The request conflicts with existing data |

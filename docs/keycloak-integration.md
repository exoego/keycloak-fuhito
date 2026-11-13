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
- An employment stores the ID of a Keycloak user. The API checks that the user exists in the realm.
- Liquibase creates and updates the tables when Keycloak starts.
- The changelog is `META-INF/fuhito/changelog.xml` in the jar.

## Overlap rules in PostgreSQL

The application checks that periods do not overlap.
On PostgreSQL the database checks it again with exclusion constraints.

- One org unit has no two sets of details on the same date.
- One org unit has no two parents on the same date in one hierarchy.
- One employment has no two sets of terms on the same date.
- One assignment has no two sets of details on the same date.
- One employment has no two primary assignments on the same date.

The constraints need the `btree_gist` extension, and the extension creates it when Keycloak starts.
On PostgreSQL 13 or later, the owner of the database may create it.

When the database user may not create the extension, Keycloak still starts.
The log has one error from Liquibase that says `permission denied to create extension "btree_gist"`.
The constraints are then missing, and only the application checks the rules.

To get the constraints, an administrator runs `CREATE EXTENSION btree_gist` in the database.
The next start of Keycloak adds them.

Other databases have only the checks of the application.

## Removal of a realm or a user

When a realm is removed, the extension deletes every row of that realm in the same transaction.

When a user is removed, the extension deletes the employments of that user in the same transaction.
The terms and the assignments of those employments go with them.
This removes the history of the person too.
To keep the history of a person who left, end the employment and disable the user. Do not remove the user.

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

## Admin events

Every successful write records an admin event, the same as the built-in admin endpoints do.
The realm stores the events when admin events are turned on in its settings.

| Resource type | Operations | Resource path |
|---|---|---|
| `FUHITO_REF_ITEM` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/ref-items/{id}` |
| `FUHITO_ORG_UNIT` | `CREATE`, `UPDATE` | `fuhito/org-units/{id}`, and the same with `/parent` or `/end` |
| `FUHITO_EMPLOYMENT` | `CREATE`, `UPDATE` | `fuhito/employments/{id}`, and the same with `/terms` or `/end` |
| `FUHITO_ASSIGNMENT` | `CREATE`, `UPDATE` | `fuhito/assignments/{id}`, and the same with `/details` or `/end` |

The event has the new state as its representation when the realm keeps representations.
A failed request records no event.

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

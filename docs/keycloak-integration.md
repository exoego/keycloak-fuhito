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
So do the change log entries about them.
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

## Endpoints

| Path | Purpose |
|---|---|
| `/ref-items` | The lists that a realm defines, such as unit types and occupations |
| `/org-units` | Org units, their tree on a date, and their history |
| `/employments` | Employments of users, with their terms over time |
| `/assignments` | Assignments of employments to org units |
| `/staff` | A shortcut that starts an employment and adds its first assignment in one call |
| `/org-units/{id}/members` | Who works in an org unit on a date, with or without the units below |
| `/users/{userId}/assignments` | Where a user works on a date |
| `/users/{userId}/history` | Every employment and assignment of a user |
| `/reorganizations` | Many org unit changes on one date, in one transaction |
| `/imports` | Many rows in one request, with a dry run |
| `/change-orders` | Approved instructions that cause changes, with their approver |
| `/change-orders/{id}/changes` | The writes that belong to a change order |
| `/change-log` | The writes to one resource |
| `/hierarchies` | The hierarchies of the realm |

## Admin events

Every successful write records an admin event, the same as the built-in admin endpoints do.
The realm stores the events when admin events are turned on in its settings.

| Resource type | Operations | Resource path |
|---|---|---|
| `FUHITO_REF_ITEM` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/ref-items/{id}` |
| `FUHITO_ORG_UNIT` | `CREATE`, `UPDATE` | `fuhito/org-units/{id}`, and the same with `/parent` or `/end` |
| `FUHITO_EMPLOYMENT` | `CREATE`, `UPDATE` | `fuhito/employments/{id}`, and the same with `/terms` or `/end` |
| `FUHITO_ASSIGNMENT` | `CREATE`, `UPDATE` | `fuhito/assignments/{id}`, and the same with `/details` or `/end` |
| `FUHITO_CHANGE_ORDER` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/change-orders/{id}` |

The event has the new state as its representation when the realm keeps representations.
A failed request records no event.

## Change log

Every successful write also adds an entry to the change log of the extension.
The log is kept whether or not the realm stores admin events.

An entry has the time, the user who did the write, the resource type, the resource ID, the operation, the path, and the state of the resource after the write.
Entries are only added. No endpoint changes or removes one.

A write belongs to a change order when its request has the query parameter `changeOrder` with the ID of the order.
For example, `PUT /org-units/{id}/end?changeOrder={orderId}`.
A request that names an unknown change order gets 400 and writes nothing.

The valid period of a row says when a fact is true.
The change log says when the system learned it, from whom, and under which approval.

When a user is removed, the log loses the entries about the employments and assignments of that user.
It keeps the entries that the user wrote as an administrator.

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

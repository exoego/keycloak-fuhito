# Keycloak integration

This document explains how the extension plugs into Keycloak.
The integration tests check the tables, the entities, and the admin REST API against Keycloak 26.8.
The notes on `kc.sh` come from the Keycloak documentation.

## Unsupported Keycloak APIs

The extension uses four Keycloak APIs that Keycloak does not promise to keep stable.

| API | Use |
|---|---|
| `JpaEntityProvider` | Own tables in the Keycloak database |
| `AdminRealmResourceProvider` | Own endpoints in the admin REST API |
| `AdminPermissionEvaluator` | Permission checks for the caller |
| `AbstractOIDCProtocolMapper` | The protocol mapper for tokens |

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

## Assignments in tokens

The protocol mapper "Org assignments" adds the assignments of the user that hold today to tokens.
Its provider ID is `fuhito-assignments-mapper`.
Add it to a client or to a client scope, the same as any other mapper.

- The claim name can be set on the mapper. The default is `assignments`.
- The mapper can add the claim to the access token, the ID token, and the user info response.
- The primary assignment comes first.
- A user with no assignment gets an empty list.
- "Today" is the date in the time zone of the server.

Each entry has these fields. A field with no value is left out.

| Field | Meaning |
|---|---|
| `assignmentId` | The ID of the assignment |
| `orgUnitId`, `orgUnitName`, `orgUnitCode` | The org unit |
| `orgUnitPath` | The units from the root down to the org unit, each with `id` and `name` |
| `jobTitle`, `occupation`, `kind` | The codes of the reference items |
| `head`, `primary` | The flags of the assignment |
| `staffNumber` | The staff number of the employment |
| `status` | The employment status today |

The claim grows with the number of assignments and the depth of the tree.
Add the mapper only to clients that need it.
The claim is as old as the token. An application that needs the state of this minute should call the API.

The mapper does not need Keycloak Organizations.

## Keycloak Organizations

The extension does not need Keycloak Organizations. A realm with Organizations turned off works.

An org unit can be linked to a Keycloak organization.
The link says that the unit, for example a hospital, stands for that organization.

- `PUT /org-units/{id}/organization` with `{"organizationId": "..."}` sets the link.
- `DELETE /org-units/{id}/organization` removes it.
- One organization is linked to at most one org unit.
- When the organization is removed in Keycloak, the link is removed too. The org unit stays.

The Organizations API of Keycloak changes between minor versions.
Only the class `KeycloakOrganizations` calls it.

### Projection into organization groups

The projection mirrors the org structure of today into the groups of Keycloak organizations.
Keycloak features that work on organization groups can then use it, for example role mappings on a group.

For each org unit that is linked to a Keycloak organization, a rebuild does this.

- Each unit below the linked unit becomes a group of the organization, with the same nesting.
- Each assignment that holds today becomes a membership of the group of its unit.
- Each user with an assignment in the linked unit or below becomes an unmanaged member of the organization. So one person can belong to several organizations.

The linked unit itself has no group. The organization stands for it.

#### How to turn it on and run it

The projection is off unless the realm has the attribute `fuhito.projection.enabled` with the value `true`.

`POST /projection/rebuild` runs it. The response counts what changed.
A rebuild with no change in between changes nothing.

Nothing else runs the projection. A write to the API does not, and no timer does.
Run a rebuild after changes, and once a day. A dated change becomes true on its date with no write on that day, so only a daily rebuild picks it up.

#### What the projection owns

The projection goes one way. The tables of this extension stay the source of truth.

- A group that the projection made has the attribute `fuhito.orgUnitId`. The projection renames, moves, and removes these groups to follow the org units.
- A rename or a move keeps the group and its ID. So role mappings on the group stay.
- A member that someone added by hand to such a group is taken out at the next rebuild.
- A group that someone made by hand has no such attribute. The projection leaves it and its members alone, unless it sits below a group that the projection removes.
- The projection never takes a user out of an organization. A person who left stays a member of the organization until an administrator removes them.
- Two units below one parent must have different names, because Keycloak requires it for groups. A rebuild fails with `projection.duplicate_name` otherwise, and changes nothing.

The projection records no admin events and no change log entries. It changes data of Keycloak, not data of the extension.

## Removal of a realm or a user

When a realm is removed, the extension deletes every row of that realm in the same transaction.

When a user is removed from Keycloak, the extension keeps the history of that person.
In the same transaction it ends only what would still hold.

- An employment that is open on that day ends on that day, with its terms, its assignments, and their reporting lines.
- An employment that would start on that day or later is removed, because it never held.
- A reporting line where the user is the manager ends on that day.
- Qualifications stay as they are.
- The change log keeps every entry. It gets one more for each employment that ended. That entry has no acting user.

So the person no longer shows up as a current member, in tokens, or in the projection.
Every read about the user still works with the user ID, for example `GET /users/{userId}/history`.

### Masking the data of one user

`POST /users/{userId}/mask` clears what identifies a person who left, and keeps every record.
See [personal-data.md](personal-data.md) for what it clears, what it keeps, and what it does not do.

### Erasing the data of one user

`DELETE /users/{userId}/data` erases everything about one user for good.
It is the only way to erase. Applications that refer to a record by ID find nothing afterwards, so prefer masking when the records must stay.

It removes the employments with their terms and assignments, the qualifications, the reporting lines of the user as a report and as a manager, and the change log entries about them.
It works for a user who is still in Keycloak and for one who was removed.
The change log gets one entry that says who erased the data of which user ID. It has nothing else about the person.

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
| `/users/{userId}/mask` | Clears what identifies a person who left, and keeps the records |
| `/users/{userId}/data` | Erases everything about one user, for a request to delete personal data |
| `/qualifications` | Licenses and certificates of users |
| `/reporting-lines` | Links from an assignment to a manager |
| `/users/{userId}/managers` | The managers of a user on a date |
| `/users/{userId}/reports` | The direct reports of a user on a date |
| `/users/{userId}/qualifications` | The qualifications of a user on a date, or all of them |
| `/reorganizations` | Many org unit changes on one date, in one transaction |
| `/imports` | Many rows in one request, with a dry run |
| `/imports/csv/{list}` | The same from one CSV file |
| `/export` | All data of the realm, with every period of every resource |
| `/projection/rebuild` | Mirrors the org structure into the groups of Keycloak organizations |
| `/attribute-sync/run` | Builds assignments from user attributes that an identity provider fills |
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
| `FUHITO_QUALIFICATION` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/qualifications/{id}` |
| `FUHITO_REPORTING_LINE` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/reporting-lines/{id}`, and the same with `/end` |

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

Removal of a user from Keycloak removes no entry.
Only the erase endpoint removes the entries about one user.

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

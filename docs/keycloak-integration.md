# Keycloak integration

This document explains how the extension plugs into Keycloak.
The integration tests check the tables, the entities, and the admin REST API against Keycloak 26.8.
The notes on `kc.sh` come from the Keycloak documentation.

## Unsupported Keycloak APIs

The extension uses these Keycloak APIs that Keycloak does not promise to keep stable.

| API | Use |
|---|---|
| `JpaEntityProvider` | Own tables in the Keycloak database |
| `AdminRealmResourceProvider` | Own endpoints in the admin REST API |
| `AdminPermissionEvaluator` | Permission checks for the caller |
| `AbstractOIDCProtocolMapper` | The protocol mapper for tokens |
| `TimerProvider` and `ClusterAwareScheduledTaskRunner` | The timer of the projection |

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
- Each assignment is judged on the date of its own org unit. That is the date of the realm unless a unit sets its own time zone. See "What today is" below.

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

A timer inside Keycloak also runs it, for every realm that turned the projection on.
A dated change becomes true on its date with no write on that day, so only a run on that day picks it up.
A write to the API does not run the projection. The timer picks the change up at its next run.

- The timer runs every 15 minutes by default.
- The server option `spi-admin-realm-restapi-extension--fuhito--projection-interval-seconds` sets the number of seconds between two runs. `0` turns the timer off.
- There is no time of day to set. Each realm and each org unit can change its date at another moment, so the timer runs through the whole day.
- In a cluster, only one node runs the projection at a time. The extension uses the same runner as the scheduled tasks of Keycloak itself. A run with nothing to change writes nothing.
- A failure in one realm is logged and does not stop the other realms.
- A realm with no usable time zone is skipped, and the log has a warning.

For example, this starts Keycloak with a run every hour.

```sh
kc.sh start --spi-admin-realm-restapi-extension--fuhito--projection-interval-seconds=3600
```

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

`POST /people/{id}/mask` clears what identifies a person who left, and keeps every record.
`POST /users/{userId}/mask` does the same for the person of a user.
See [personal-data.md](personal-data.md) for what it clears, what it keeps, and what it does not do.

### Erasing the data of one user

`DELETE /people/{id}/data` erases everything about one person for good.
`DELETE /users/{userId}/data` does the same for the person of a user.
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

## What today is

Several things depend on what today is. They are the default of `asOf`, the assignments in tokens, the projection, the sync from user attributes, the end date that the removal of a user writes, and the check before masking.

The date never comes from the time zone of the server.
Each realm says where it is with two realm attributes.

| Realm attribute | Meaning | Default |
|---|---|---|
| `fuhito.timeZone` | The time zone of the realm, for example `Asia/Tokyo` | None. A realm must set it. |
| `fuhito.dayStart` | The time of day when the date changes, as `HH:mm` | `00:00` |

With a day start of `04:00`, the date changes at 04:00 in the time zone of the realm.
At 03:59 on April 1, today is still March 31.

A realm with no time zone gets 409 with `realm.time_zone_missing` from every endpoint.
An unknown time zone gets `realm.time_zone_invalid`, and a bad day start gets `realm.day_start_invalid`.

Two paths must not fail because of a setting. They are a login with the token mapper and the removal of a user.
When the time zone is missing or bad there, UTC decides what today is, and the log has a warning.

Stored dates are plain calendar dates. They are never converted between time zones.
The time zone matters only to decide which date it is now.

### An org unit in another time zone

A realm can have org units in more than one time zone, for example a branch in another country.
Any org unit can set its own time zone, its own day start, or both.

- `PUT /org-units/{id}/time-settings` with `{"timeZone": "America/New_York", "dayStart": "04:00"}` sets them.
- A value that is left out is removed. An empty body removes both.
- An unknown time zone gets 400 with `org_unit.time_zone_invalid`, and a bad day start gets `org_unit.day_start_invalid`.
- `GET /org-units/{id}` shows the two values that the unit itself sets.

The units below use the same settings.
The nearest setting at the unit or above it wins, and the realm decides the rest.
The time zone and the day start are looked up one by one. So a unit can set only a day start and keep the time zone from above.
The parents of today, as the realm sees it, are used for this.

The date of the org unit decides these.

| What | Which date |
|---|---|
| A read about one org unit with no `asOf`. They are the unit itself, its children, its subtree, its path, and its members. | The date of that unit |
| `GET /users/{userId}/assignments` with no `asOf`, and the assignments in tokens | Each assignment is judged on the date of its own unit |
| The projection into organization groups | The date of the linked unit, for everything below it |
| The sync from user attributes | The date of the org unit that the department matches |

The date of the realm decides everything else.
That is `GET /org-units` with no `asOf`, the employments, the qualifications, the managers and the reports, the end date that the removal of a user writes, and the check before masking.

A person can work in two time zones.
A change on April 1 then shows in one of them some hours before the other.
That is on purpose. Each place sees the change when its own April 1 starts.

A read with `asOf` never depends on a time zone.

On a day when the clocks change for summer time, the date can change up to one hour early or late when the day start is not `00:00`.

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
The default is today, as the realm defines it. See "What today is" below.

## Endpoints

| Path | Purpose |
|---|---|
| `/ref-items` | The lists that a realm defines, such as unit types and occupations |
| `/org-units` | Org units, their tree on a date, and their history |
| `/people` | The people that the realm knows, with or without a Keycloak user |
| `/employments` | Employments of people, with their terms over time |
| `/assignments` | Assignments of employments to org units |
| `/staff` | A shortcut that starts an employment and adds its first assignment in one call |
| `/org-units/{id}/members` | Who works in an org unit on a date, with or without the units below |
| `/people/{id}/assignments` | Where a person works on a date |
| `/people/{id}/history` | Every employment and assignment of a person |
| `/people/{id}/mask` | Clears what identifies a person who left, and keeps the records |
| `/people/{id}/data` | Erases everything about one person, for a request to delete personal data |
| `/qualifications` | Licenses and certificates of users |
| `/reporting-lines` | Links from an assignment to a manager |
| `/people/{id}/managers` | The managers of a person on a date |
| `/people/{id}/reports` | The direct reports of a person on a date |
| `/people/{id}/qualifications` | The qualifications of a person on a date, or all of them |
| `/users/{userId}/...` | A shortcut to each `/people/{id}/...` path above, for the person of a Keycloak user |
| `/reorganizations` | Many org unit changes on one date, in one transaction |
| `/imports` | Many rows in one request, with a dry run |
| `/imports/csv/{list}` | The same from one CSV file |
| `/export` | All data of the realm, with every period of every resource |
| `/projection/rebuild` | Mirrors the org structure into the groups of Keycloak organizations |
| `/attribute-sync/run` | Builds assignments from user attributes that an identity provider fills |
| `/change-orders` | Approved instructions that cause changes, with their approver |
| `/change-orders/{id}/changes` | The writes that belong to a change order |
| `/change-log` | The writes to the realm, to follow from a sequence number or to narrow to one resource |
| `/hierarchies` | The hierarchies of the realm |

[openapi.yaml](openapi.yaml) lists every endpoint with its request and its response, as OpenAPI 3.2.
The build generates it from the annotations of the REST resources.

## People and their names

A person record can have a name of its own.
When it has none and it is linked to a Keycloak user, a response has the name that Keycloak keeps for the user.
The field `nameSource` says which one it is, `RECORD` or `KEYCLOAK`.
So a product does not need to ask Keycloak for the name.

Keycloak keeps a first name and a last name. The extension joins them with a space.

| Realm attribute | Meaning | Default |
|---|---|---|
| `fuhito.nameOrder` | `given-first` or `family-first` | `given-first` |

A person with no linked user must have a name in the record.

An employment and a qualification have `personId`. They also have `userId` when the person is linked to a user.
A reporting line has `managerPersonId`, and `managerUserId` in the same way.
A request to create one of them takes either ID. With the ID of a user, the person of the user is used, and created when the user has none.
A person with no linked user gets no token claim and no group in the projection.

### Paths by user

Each `/users/{userId}/...` path is a shortcut to the same path of the person that is linked to the user.

- A user that Keycloak does not know, and that no person is linked to, gets 404.
- A user with no person gets empty lists and an empty history.
- A mask of a user with no person creates the person, so that the masking is remembered.
- An erase of a user with no person does nothing.

## Admin events

Every successful write records an admin event, the same as the built-in admin endpoints do.
The realm stores the events when admin events are turned on in its settings.

| Resource type | Operations | Resource path |
|---|---|---|
| `FUHITO_REF_ITEM` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/ref-items/{id}` |
| `FUHITO_ORG_UNIT` | `CREATE`, `UPDATE` | `fuhito/org-units/{id}`, and the same with `/parent`, `/end`, `/organization`, or `/time-settings` |
| `FUHITO_PERSON` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/people/{id}` |
| `FUHITO_PERSON_DATA` | `UPDATE` for a mask, `DELETE` for an erase | `fuhito/people/{id}/mask`, `fuhito/people/{id}/data` |
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

An entry has its sequence number in the realm, the time, the user who did the write, the resource type, the resource ID, the operation, the path, and the state of the resource after the write.
Entries are only added. No endpoint changes or removes one.
Another system can follow the log from a sequence number. See [history.md](history.md).

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

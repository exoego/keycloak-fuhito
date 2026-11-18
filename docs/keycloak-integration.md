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
- No two org units have the same code on the same date.
- One reference item has no two sets of names on the same date.

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
- A user with two employments at the same time can have a primary assignment in each. Then the one of the employment that started first comes first. The same order holds for `GET /users/{userId}/assignments`.
- A user with no assignment gets an empty list.
- Each assignment is judged on the date of its own org unit. That is the date of the realm unless a unit sets its own time zone. See "What today is" below.

Each entry has these fields. A field with no value is left out.

| Field | Meaning |
|---|---|
| `assignmentId` | The ID of the assignment |
| `orgUnitId`, `orgUnitName`, `orgUnitCode` | The org unit |
| `orgUnitPath` | The units from the root down to the org unit, each with `id` and `name` |
| `jobTitle`, `occupation`, `kind` | The codes of the reference items |
| `effectiveWorkLocation`, `effectiveCostCenter` | The codes of the work location and the cost center that apply today. They are the ones of the assignment, else of its org unit, else of the nearest unit above in the default hierarchy |
| `head`, `primary` | The flags of the assignment |
| `staffNumber` | The staff number of the employment. Only with the setting "Add staff number" |
| `status` | The employment status today. Only with the setting "Add employment status" |

Every client that gets the token can read the claim. So the staff number and the status are off by default.
The status tells, for example, that a person is on leave. The leave reason is never in the claim.

The claim grows with the number of assignments and the depth of the tree.
Add the mapper only to clients that need it.
The claim is as old as the token. An application that needs the state of this minute should call the API.

The mapper does not need Keycloak Organizations.

## Keycloak Organizations

The extension does not need Keycloak Organizations. A realm with Organizations turned off works.

An org unit can be linked to a Keycloak organization.
The link says that the unit, for example a hospital, stands for that organization.

- `PUT /org-units/{id}/organization` with `{"version": 3, "organizationId": "..."}` sets the link.
- `DELETE /org-units/{id}/organization?version=3` removes it.
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
- A user that the projection added leaves the organization again when no assignment of today puts the user there. Members that someone else added stay. See "What the projection owns" below.

The linked unit itself has no group. The organization stands for it.

#### How to turn it on and run it

The projection is off unless the realm has the attribute `fuhito.projection.enabled` with the value `true`.

`POST /projection/rebuild` runs it. By default it looks only at what changed since the last run. See "A run of the changes" below.
`POST /projection/rebuild?full=true` runs a full rebuild. The response counts what changed.
A rebuild with no change in between changes nothing.

A first rebuild of a large realm takes many minutes, and the call stays open for that time. A proxy or a load balancer between the caller and Keycloak may end the call first. The rebuild goes on in the server anyway.
`GET /projection/last-run` reads the last run, also one that the timer started. It has `state`, `startedAt`, `endedAt`, the counts of the report, and the code of an error.
The state is `RUNNING`, `DONE`, or `FAILED`. A run that still shows `RUNNING` after six hours shows `ABANDONED`, for example because its node stopped.

A rebuild does its work in pieces, and each piece is one transaction. One transaction for a large realm would run into the transaction timeout of Keycloak.
So a rebuild that fails in the middle keeps the pieces that it finished. The groups are then partly up to date. The next rebuild does the rest.

Only one rebuild runs for a realm at a time, on all nodes of a cluster. A second call fails with `projection.running` and the status 409.
The timer skips a realm whose rebuild is still going on, and logs a warning.
The lock goes away by itself after six hours, for the case that a node dies with it.

A rebuild reads who is in which group from the table of Keycloak, for many groups in one query.
A user that only a user storage provider holds is not in that table. A user that Keycloak imported, for example from LDAP, is in it.
A user is outside the table when a custom user storage provider keeps the users in another database, or when an LDAP federation has "Import Users" off.
For such a user, each rebuild adds the memberships again and counts them again. It never takes the user out of a group.
The membership of an organization is different. A rebuild asks Keycloak itself about such a user, and Keycloak tells that the user is a member already. So the rebuild records the user once, and writes and counts nothing more. It takes the user out of the organization like any other member that it added.

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

#### A run of the changes

`POST /projection/rebuild` looks only at what the change log names since the last run that was done. With `full=true`, it runs a full rebuild instead. The timer runs full rebuilds.

- Each run that is done, full or of the changes, keeps the sequence number of the newest entry of the change log when it started. A run of the changes reads the entries after the number of the last run that was done. A run that fails keeps the number of the run before it.
- An entry of an assignment, an employment, or a person names the users of the person. The run compares the groups and the organizations of each such user with what the assignments of today give. For a person who changed, it also looks at the user that the person was linked to before. It reads that user from the change log.
- An entry of an org unit names the group of the unit and the groups below it, in each organization and in the realm group tree. That is where the units are now and where their groups are now. A user who joins or leaves with such a group is looked at too.
- An entry of a linked unit mirrors its whole organization again. So does a new time setting at the linked unit or above it, because it can change the date of the organization.
- A new time setting also mirrors the whole realm group tree again. So does any change of an org unit while the roots of the tree are not all on one date.
- An organization that no unit of today links loses what the projection made there, as in a full rebuild.
- An entry of a reference item, a kind, an attribute definition, a reporting line, a role over an org unit, a qualification, an ID of another system, a change order, or a mask changes nothing that the projection reads. The run skips it.
- The run reads today's state of what it looks at, as a full rebuild does. A dated change that becomes true later with no write is not in the change log, so only a full rebuild picks it up. So does a change made by hand in Keycloak.
- The run asks for the permission to manage only the organizations that it may change. It asks for the permission to manage groups only when the realm uses realm groups and something changed.

So a full rebuild right after a run of the changes finds nothing to change, when no write came in between.

In some cases a run of the changes runs a full rebuild instead. Its report then has `fellBack` with `true`, and the reason in `fallbackReason`.

| Reason | When |
|---|---|
| `no_earlier_run` | No run was done before. |
| `settings` | The realm group or the use of organizations is not the same as at the last run, or the realm group cannot be read. These are not in the change log. |
| `restore` | A restore wrote the data again. |
| `hierarchy` | A hierarchy changed. The default hierarchy decides the tree. |
| `erasure` | The data of a person was erased, with the entries about the person. |
| `earlier_state` | The change log does not tell an earlier state that the run needs. For example, a person changed who has no earlier entry, as after a restore. |
| `unknown_entry` | An entry of a type that the run does not know. |

The report of a run of the changes has three more counts. A full rebuild that was asked for has none of them.

| Count | What it counts |
|---|---|
| `changeLogEntries` | The entries that the run read. |
| `usersLookedAt` | The users whose groups and organizations the run compared. |
| `organizationsLookedAt` | The organizations that the run may change, for its org units and for its users. |

See [performance.md](performance.md) for how long each kind of run takes.

#### What the projection owns

The projection goes one way. The tables of this extension stay the source of truth.

- A group that the projection made has the attribute `fuhito.orgUnitId`. The projection renames, moves, and removes these groups to follow the org units.
- A rename or a move keeps the group and its ID. So role mappings on the group stay.
- A member that someone added by hand to such a group is taken out at the next rebuild.
- A group that someone made by hand has no such attribute. The projection leaves it and its members alone, unless it sits below a group that the projection removes.
- Keycloak does not record who added a member to an organization. So the projection records each user that it adds, in the table `FUHITO_PROJECTED_MEMBER`.
- A rebuild takes a recorded user out of the organization when no assignment of today puts the user there. For example, the person moved to a unit of another organization, or the assignment ended. The record goes too. Keycloak also takes the user out of every group of that organization.
- A member with no record stays. These are the members that an administrator or an identity provider added. A user who was a member before the projection would add them gets no record, so that user stays too.
- A managed member stays, also with a record, because Keycloak would remove a managed member from the realm. The record goes.
- When an administrator takes a recorded member out by hand, the next rebuild removes the record. If an assignment still puts the user there, the rebuild adds the user again with a new record.
- The records of a user go when Keycloak removes the user. The records of an organization go when Keycloak removes the organization.
- An organization that no unit links on its date loses every group that the projection made there, for example when the linked unit ends or its link is taken away. It also loses every recorded member. Its other members stay.
- While organizations are off, because the server runs without the feature or the realm turned them off, a rebuild leaves every organization and every record alone. It still mirrors the realm groups. When a unit links an organization, the rebuild writes one warning to the server log. The first rebuild after organizations are on again takes out the recorded members that should go.
- The report has `organizationMembersAdded` and `organizationMembersRemoved`. They count the users that the rebuild added to an organization and took out of one.
- Two units below one parent must have different names, because Keycloak requires it for groups. A rebuild fails with `projection.duplicate_name` otherwise, and changes nothing in that organization. The organizations that the rebuild handled before it stay as the rebuild left them.

The projection records no admin events and no change log entries. It changes data of Keycloak, not data of the extension.
The records of the members that it added describe Keycloak too. So an export does not have them, and a restore keeps them.

### Projection into realm groups

The projection can also mirror the whole tree into realm groups. A client that uses realm groups or realm roles can then use it, and so can a realm with Organizations turned off.

- The realm attribute `fuhito.projection.realmGroup` names a realm group by its path, for example `/org`. An administrator makes that group first.
- Every root of the default hierarchy becomes a group right below it, with the units below the root nested the same way.
- Each assignment that holds today becomes a membership of the group of its unit. There is nothing like the members of an organization.
- Each root is mirrored as it is on its own date.
- The rules of what the projection owns are the same as for organization groups. Two roots must have different names too.
- A path that names no group fails the rebuild with `projection.realm_group_unknown`.
- The projection must be on, with `fuhito.projection.enabled`. Organizations and realm groups can both be used. A unit is then in both.
- The report of a rebuild has `realmGroupTrees`, which is 1 when the tree went into realm groups.

## Removal of a realm or a user

When a realm is removed, the extension deletes every row of that realm in the same transaction.

When a user is removed from Keycloak, the extension keeps the history of that person.
In the same transaction it ends only what would still hold.

- An employment that is open on that day ends on that day, with its terms, its assignments, and their reporting lines.
- An employment that would start on that day or later is removed, because it never held.
- A reporting line where the user is the manager ends on that day.
- Qualifications stay as they are.
- The records of the organizations that the projection added the user to are removed.
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

- `PUT /org-units/{id}/time-settings` with `{"version": 3, "timeZone": "America/New_York", "dayStart": "04:00"}` sets them.
- A value that is left out is removed. A body with only `version` removes both.
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

### Roles

The extension adds three roles to the client `realm-management` of every realm. Keycloak keeps its own admin roles there, such as `manage-users` and `manage-organizations`.

| Role | What it gives | Contains |
|---|---|---|
| `fuhito-view-structure` | Reads of the org structure | |
| `fuhito-view-people` | Every read, the data of people included | `fuhito-view-structure` |
| `fuhito-manage` | Every read and every write | `fuhito-view-people` |

- Give a role to the service account of each application that calls the API. An application that only draws the tree gets `fuhito-view-structure`, and learns no grade, no status, and no staff number.
- The roles of Keycloak for users, such as `query-users`, `view-users`, and `manage-users`, give no access to the API. So an application that writes the org structure needs no power over the users of Keycloak.
- `realm-admin` contains `fuhito-manage`, so an administrator of the realm may do everything.
- The master realm has a client `{realm}-realm` for each realm. It gets the same three roles, and the role `admin` of the master realm contains its `fuhito-manage`. An administrator in the master realm checks those the same way as the admin roles of Keycloak.
- The extension adds the roles to every realm when the server starts, and to a new realm when it is created.
- The roles have the prefix `fuhito-`, so that no role that Keycloak adds later has the same name.
- Fine-grained admin permissions do not apply to the data of the extension. They still apply to the data of Keycloak below.

The org structure is the org units with their children, subtree, path, and history, the hierarchies, the reference items with the history of their names, their kinds, the definitions of free attributes, and the version.
Every other read has data of people, and needs `fuhito-view-people`. The members of an org unit and the holders of roles over it are among them.
A reader of the structure sees the ID of the Keycloak organization that an org unit links. A reader of people also sees its alias in an export. Neither tells anything else about the organization.

### Data of Keycloak

A request that changes data of Keycloak also asks for what Keycloak asks for that change.

| Operation | Also needs |
|---|---|
| Link an org unit to a Keycloak organization, or remove the link | The permission to manage that organization, such as `manage-organizations` |
| `POST /projection/rebuild` | The permission to manage every organization that the rebuild may change. With realm groups, also the permission to manage groups, such as `manage-users` |
| A mask with `clearKeycloakName=true` | The permission to manage that user, such as `manage-users` |

A link to an organization decides who is a member of it at the next rebuild of the projection. So it asks for the same permission as a change of the members through the admin API of Keycloak. A link that replaces another one asks for it on both organizations.

The organizations that a rebuild may change are those that an org unit links, and those that keep groups of an earlier rebuild.
The rebuild checks them all before it writes anything. The timer of the projection has no caller and checks nothing.

An end of an org unit with a link asks for nothing more. The link gave the members of the organization to the data of the extension, the same as an assignment does. The next rebuild then changes the organization.

### Plans that are not announced

A plan for a reorganization is often secret until it is announced.
The realm attribute `fuhito.structure.daysAhead` keeps a caller with only `fuhito-view-structure` from reading the structure too far ahead.

| Realm attribute | Value | Default |
|---|---|---|
| `fuhito.structure.daysAhead` | The number of days after today that the caller may read, 0 or more | None. The caller reads every date. |

With this attribute, such a caller is limited in these ways.

- A read with a later `asOf` gets 403 with `structure.too_far_ahead`. Today is the date of the realm.
- The history of an org unit has every period, so a read of it gets 403 too. So does the history of a reference item.
- The successions of an org unit leave out those after the limit.
- An org unit shows no end of its details after the limit, because that is the date of a later change. A reference item shows no end of its names after the limit for the same reason.
- `GET /external-ids/lookup` finds no org unit that starts after the limit.

A read with no `asOf` reads today. For an org unit with its own time zone, that can be the day after the date of the realm.
A caller with `fuhito-view-people` has no limit.
A value that is not such a number gets 409 with `structure.days_ahead_invalid` for the callers that it limits.

### Responses

A request with no token gets 401.
A caller without the role or the permission gets 403.

A response leaves out every field that has no value.
For example, an org unit with no end date has no `validTo` field.

## Dates

Dates have the form `YYYY-MM-DD`.
A period includes its `validFrom` date and does not include its `validTo` date.

Read endpoints take an `asOf` date.
The default is today, as the realm defines it. See "What today is" below.

A list that grows with the realm is read in pages. These lists take `first` and `max`.

- `GET /org-units`, and the children, the subtree, the members, and the role holders of an org unit.
- `GET /people`, and the reports and the unit roles of a person. The same reads by the ID of a Keycloak user under `/users/{userId}` are paged too.
- `GET /employments`, `GET /assignments`, `GET /qualifications`, and `GET /unit-roles`.
- `GET /hierarchies` and `GET /ref-items`.
- `GET /change-orders`, the writes of a change order, `GET /change-log`, and `GET /effective-changes`.

`first` is the number of entries to skip. `max` is the largest number of entries to return.
`max` is 100 by default and 1000 at most. A larger value counts as 1000.
A read with fewer entries than `max` has reached the end.

A list that has a cursor is better read with it. These are the lists of org units, people, employments, assignments, qualifications, and unit roles, and the members of an org unit.

- A response with a full page has the header `Link` with the URL of the next page and `rel="next"`. Follow it until a response has no such header.
- The URL has the parameter `after` with a cursor. The cursor says where the next page starts, so a late page costs as much as the first one. With `first`, the database counts past every skipped row.
- A full page can be the last one. The next page is then empty and has no header.
- A cursor is plain text. It is the name of its format and the values of the last row of the page, for example `people.v1__<person ID>` or `members.v1__<org unit ID>__<display order>__<first date>__<assignment ID>`.
- It is not a secret. It has only values that the caller already got, and it cannot widen what the caller may read.
- The name says which list the cursor belongs to. A cursor of another list gets 400 with `invalid.cursor`.
- When the order of a list changes, its format gets a new version. A cursor of the old version gets 400, and the caller starts again from the first page.
- A request gives `after` or `first`, not both.
- The cursor of the org units has only the ID of the last unit, for example `org-units.v1__<org unit ID>`. The other values of the order come from that unit on the date of the request. A unit that does not exist on that date gets 400 with `invalid.cursor_unit`.
- The cursor of the members names the org unit of the last row. With `includeDescendants=true`, the next page starts after the place of that unit in the tree. A unit that is not in that tree on the date of the request gets 400 with `invalid.cursor_unit`.

Each paged list has a count, a read of its own at the path of the list plus `/count`. For example, `GET /org-units/{id}/members/count` counts the members of an org unit.

- It takes the filters of the list, without `first`, `max`, and `after`. It returns the number of entries on all pages, for example `{"count": 123}`.
- The database counts with the same conditions as the list. A filter with a bad value fails the same way as on the list.
- It needs the same permission as the list.
- A caller that only follows the pages does not pay for the count.
- The count of the change log counts from the first entry. It does not take the sequence number `after`.

The export of a realm, `GET /export/{list}`, is read in pages with `max` and `after`. It has no count, because a backup reads every page to the end.

The history of one resource is returned whole. So are the other lists of one person, such as the assignments, the managers, and the qualifications. The reports of a person are read in pages, because the head of a large org unit has many. So are the unit roles of a person.

`GET /org-units` lists every org unit of a date at any depth, or the ones that match its filters. The filters apply together. The list is not in tree order.
`GET /org-units?roots=true` lists the org units with no parent, the roots.
A caller goes down from them with `GET /org-units/{id}/children`, or reads one branch with `GET /org-units/{id}/subtree`.
A subtree comes in tree order. Each parent comes before its children.
Siblings follow the display order, then the phonetic name, then the name. A unit with no phonetic name comes after the ones that have it.
The list of every org unit has the same order as siblings, and then the ID.
The database orders the names, so the order of two names follows the collation of the database.
The database also walks the subtree, so a read of one page does not load it into memory.
The path of an org unit is read with a walk upward from the unit. The assignments of a person and the managers of a person use the same walk.

### Writes of two products at once

Every resource has `version`, in a read of it and in each entry of a list.
A new resource has 1, and each write counts it up by one.
A write of a part of a resource counts up the version of the whole resource.
The parts are the periods and the parents of an org unit, the terms of an employment, the periods of an assignment, the names of a reference item, and the IDs that other systems have for a resource.
A write by another endpoint or by Keycloak counts it up too, such as an import, the attribute sync, a mask, and the removal of a user or of an organization in Keycloak.
A restore keeps the versions of the export.
The work location and the cost center that apply come from other org units, so they are no part of an org unit or of an assignment. A change of a unit above does not count up the version of the units and the assignments below it.

A write of a stored resource names the version that the caller read. The write goes on only when the resource still has that version.
So a product cannot overwrite a change that another product made after its read.

- A `PUT` and a `POST` of an operation on a stored resource have `version` in the body, for example `PUT /employments/{id}/terms` and `POST /employments/{id}/leave`.
- `POST /change-orders/{id}/announce` and `POST /people/{id}/mask` have only the version in the body, for example `{"version": 3}`.
- A `DELETE` has it in the query, for example `DELETE /employments/{id}/end?version=3`.
- A write with no version fails with 400 and `version.required`, and writes nothing.
- A write with an old version fails with 409 and `version.conflict`, and writes nothing. The values of the error have the version of now in `version`. The caller reads again and decides.
- An operation that changes several resources takes the version of the resource that its path names. For example, `POST /employments/{id}/transfer` takes the version of the employment, and also ends its assignments.
- A step of `POST /reorganizations` that changes a stored unit has the version of that unit. Two steps of one unit name the same version, the one that the caller read. A step of a unit that an earlier step creates needs none.
- `PUT /external-ids` of a stored ID has the version of that ID, and `DELETE /external-ids` has it in the query. A new ID needs none.
- A create needs no version. The import, the attribute sync, the restore, and the rebuild of the projection need none either, because they load data from the system of record or from Keycloak.
- `POST /employments/transfers` and the writes of `/users/{userId}` need no version. Their path names no resource with a version.
- A write locks the stored row of the resource until it ends. A second write with the same version at the same moment waits, reads the new version, and fails with 409.
- A write can also change rows of other resources, such as the reporting lines that a transfer ends. When another write changes such a row at the same moment, the later write fails with 409 and `version.conflict` too. Its error has no version, because the conflict was on another resource.

For example, a product reads an employment, and changes its terms with the version that it read.

```
GET /employments/e-1
200 {"id": "e-1", "version": 3, ...}

PUT /employments/e-1/terms
{"version": 3, "validFrom": "2026-10-01", "workPercent": 80}
200 {"id": "e-1", "version": 4, ...}
```

A second product read the same employment before that write. Its own write with version 3 fails, and the employment keeps the terms of the first write.

```
PUT /employments/e-1/terms
{"version": 3, "validFrom": "2026-10-01", "workPercent": 50}
409 {"error": "version.conflict", "params": {"version": "4"}, "message": "The resource changed since it was read. ..."}
```

### A request sent again

A `POST` that creates something or runs an operation can have the header `Idempotency-Key`, a text of up to 255 characters that the caller makes, for example a UUID.

- A second request with the same key, the same caller, the same path and query, and the same body gets the first response again, and writes nothing. This makes a retry after a timeout safe.
- The same key with another body, another query such as another `changeOrder`, or another caller fails with 422 and `idempotency.key_reused`.
- A key whose first request is still running fails with 409 and `idempotency.in_progress`.
- A key works for 24 hours. A request with no key works as before.
- A first request that failed stores nothing, so the same key can try again.
- A mask or an erase of a person clears the stored body of a response that names the person. The same request then gets the first status with no body. See [personal-data.md](personal-data.md).

### Hierarchies

Every realm has the hierarchy `default`. A realm can create more with `POST /hierarchies`, for another view of the same org units.
An org unit has at most one parent in each hierarchy on a date.

- `PUT /org-units/{id}/parent`, `GET /org-units`, `GET /org-units/{id}`, `/children`, `/subtree`, `/path`, and `/members` take `hierarchy` with the code of a hierarchy. The default is `default`.
- In `GET /org-units`, `hierarchy` names the hierarchy of the `parentId` of each unit and of `roots=true`. It does not leave out the units that are not in that hierarchy.
- Every org unit is in the default hierarchy. Another hierarchy has only the units with a parent or a child in it on the date.
- The history of an org unit lists its parents in every hierarchy.
- An org unit cannot end while it has a child in any hierarchy. Its end ends its parent links in every hierarchy.
- These use the default hierarchy only. They are the `parentId` of a new org unit and of a row of the import, the reorganization, the managers that come from the head of the unit above, and the projection into Keycloak.
- The import gives parents in other hierarchies with the list `orgUnitParents`. See [import.md](import.md).

The members of an org unit with the units below come in the same tree order.
Inside one unit, they follow the display order, then the start of the assignment.
The database applies the filters, the order, and the page.

## Endpoints

| Path | Purpose |
|---|---|
| `/ref-items` | The lists that a realm defines, such as unit types and occupations |
| `/org-units` | Org units. The list has every unit of a date, found by name, code, unit type, lateral, legal entity, or root |
| `/org-units/{id}/children`, `/subtree`, `/path` | The tree of a date, one level, one branch, or the way up |
| `/org-units/{id}/succession` | The org units that a unit follows after a merger or a split, and the ones that follow it |
| `/people` | The people that the realm knows, with or without a Keycloak user |
| `/employments` | Employments of people, with their terms over time. The list finds them by staff number, employer, and date |
| `/assignments` | Assignments of employments to org units. The list has the assignments of a date across the realm, by org unit, job title, occupation, and kind |
| `/staff` | A shortcut that starts an employment and adds its first assignment in one call |
| `/org-units/{id}/members` | Who works in an org unit on a date, with or without the units below |
| `/people/{id}/assignments` | Where a person works on a date |
| `/people/{id}/history` | Every employment and assignment of a person |
| `/people/{id}/mask` | Clears what identifies a person who left, and keeps the records |
| `/people/{id}/data` | Erases everything about one person, for a request to delete personal data |
| `/qualifications` | Licenses and certificates of users. The list finds them by kind and date |
| `/reporting-lines` | Links from an assignment to a manager |
| `/unit-roles` | Roles of employments over org units that are not membership. The list has the roles of a date across the realm, by role and org unit |
| `/org-units/{id}/role-holders` | Who has a role over an org unit on a date, also from a unit above. `nearest=true` keeps only the nearest holders of each role |
| `/people/{id}/unit-roles` | The roles over org units of a person on a date |
| `/attribute-definitions` | The names and types of the free attributes of each kind of resource |
| `/ref-kinds` | The kinds of reference data, the fixed ones and the ones that the realm added |
| `/export`, `/restore` | Every stored row of the realm in pages, and the load of such an export into an empty realm |
| `/external-ids` | The IDs that systems have for a resource, and the read that finds a resource by one |
| `/people/{id}/managers` | The managers of a person on a date |
| `/people/{id}/reports` | The direct reports of a person on a date |
| `/people/{id}/qualifications` | The qualifications of a person on a date, or all of them |
| `/users/{userId}/...` | A shortcut to each `/people/{id}/...` path above, for the person of a Keycloak user |
| `/reorganizations` | Many org unit changes on one date, in one transaction |
| `/imports` | Many rows in one request, with a dry run |
| `/imports/csv/{list}` | The same from one CSV file |
| `/projection/rebuild` | Mirrors the org structure into the groups of Keycloak organizations |
| `/attribute-sync/run` | Builds assignments from user attributes that an identity provider fills |
| `/change-orders` | Approved instructions that cause changes, with their approver |
| `/change-orders/{id}/changes` | The writes that belong to a change order |
| `/change-log` | The writes to the realm, to follow from a sequence number or to narrow to one resource |
| `/assignments/{id}/move` | Moves a person to another org unit on a date, in one write |
| `/employments/{id}/transfer` | Moves a person to another employer on a date, in one write |
| `/employments/transfers` | Moves every person of one employer to another, for a merger. It can end the old employer |
| `/effective-changes` | The resources with a period that starts or ends in a range of dates |
| `/hierarchies` | The hierarchies of the realm. Create, rename, and delete |

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
| `FUHITO_UNIT_ROLE` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/unit-roles/{id}`, and the same with `/end` |
| `FUHITO_ATTRIBUTE_DEFINITION` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/attribute-definitions/{id}` |
| `FUHITO_REF_KIND` | `CREATE`, `UPDATE`, `DELETE` | `fuhito/ref-kinds/{code}` |
| `FUHITO_EXTERNAL_ID` | `UPDATE`, `DELETE` | `fuhito/external-ids/{id}`, with the ID of the stored row |

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

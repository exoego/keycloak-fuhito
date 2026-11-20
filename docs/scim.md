# SCIM

This document explains how data that an identity provider sends over SCIM reaches the org structure.

## What was checked

These facts come from the documentation and the source code of Keycloak 26.8.2.

- Keycloak has its own SCIM 2.0 server. It is supported since 26.8, and each realm turns it on.
- The server handles users and groups of the realm. It does not handle Keycloak organizations.
- The Enterprise User extension has `employeeNumber`, `costCenter`, `organization`, `division`, `department`, and `manager`.
- Keycloak stores each of them as a plain user attribute. An administrator creates a user profile attribute and maps it to the SCIM attribute.
- So the name of the user attribute is the choice of the administrator. The Keycloak documentation uses `employeeNumber`, `department`, and `manager` in its examples.
- `manager.value` is one attribute, and `manager.displayName` is another. The first holds the SCIM ID of the manager, which is the Keycloak user ID.
- The core attribute `title` is not mapped by default. An administrator maps it the same way.
- Keycloak publishes an event to extensions when a user is removed. It publishes none when a user attribute changes.

## What SCIM does not carry

An identity provider sends one department, one title, and one manager for each user, as flat text.
It sends no tree of departments, no second assignment, and no dates.

So SCIM can feed one assignment for each person.
The tree, further assignments, and history come from the API or from an import. See [import.md](import.md).

## The design

The extension does not add a SCIM endpoint of its own.
Users arrive through the SCIM server of Keycloak, and the extension reads the user attributes that it wrote.

### An explicit sync

One endpoint runs the sync for a realm. Nothing else runs it.

A listener was the other choice. It was not taken, for these reasons.

- Keycloak has no event for a changed user attribute that an extension can rely on.
- Admin events exist, but each realm turns them on or off, and a listener must be added to each realm by hand. A realm with the wrong setting would lose changes with no error.
- A sync that reads the present state is correct after any missed change. A listener is not.

The projection into organization groups works the same way, for the same reasons.

### What the sync does

For each user with a department attribute:

1. Match the department to an org unit that exists today. Try the code first, then the primary name.
2. Match the title to a job title. Try the code first, then the name of today.
3. Give the user an employment that starts today, if the user has none today. It has the source `scim` and no external ID, because the sync finds it by the user. So a user who left and comes back gets a new employment, as in "A person who comes back" in [history.md](history.md).
4. Keep one assignment for the user that the sync owns. It is marked with the source `scim`.
5. Give that assignment a solid reporting line to the manager.

When the attributes change:

- A new department ends the assignment today and adds a new one.
- A new title changes the details of the assignment from today.
- A new manager ends the reporting line today and adds a new one.
- A removed department ends the assignment.

"Today" is the date of the org unit that the department matches.
It is the date of the realm unless the unit, or one above it, sets its own time zone.
When a user moves to another unit, the date of the new unit is used.
The department is matched on the date of the realm. For some hours of a day, a unit with its own time zone is on another date, where it may not exist yet or any more. Then the department matches nothing until that changes.
A change never takes effect before the employment of the user starts. When the unit no longer exists on that date, the department matches nothing too.
See "What today is" in [keycloak-integration.md](keycloak-integration.md).

The sync never touches an assignment that it does not own.
So an administrator can add further assignments by hand, and the sync leaves them alone.

A department that matches nothing is reported, and the sync skips that user.
The user gets nothing new, and what the sync gave the user before stays as it is.
A title that matches nothing is reported, but the user is not skipped.
The sync goes on as if the user had no title. So the assignment has no title, and an assignment that had one loses it from today.
A deactivated job title stays on an assignment that has it. The sync gives it to no other assignment. There it is reported as `unknown`, and the assignment gets no title.
A manager that matches nothing is reported too, and the sync goes on as if the user had no manager.
The sync does not create org units or job titles. A typing mistake in the identity provider must not grow the tree.

An org unit must exist on every date of an assignment.
So when the org unit has a planned end, the assignment of the sync ends on the same date.
From that date, the department names no org unit that exists. It is reported, and the user is skipped as above.
When the end of the unit moves later, the assignment keeps its end. The first run on or after that end adds a new assignment.

### How to run it

`POST /attribute-sync/run` runs the sync. `?dryRun=true` reports what it would do and writes nothing.
`GET /attribute-sync/last-run` reads the state and the counts of the last run, for a caller whose long call a proxy ended. The states are the same as for the projection. See [keycloak-integration.md](keycloak-integration.md). A dry run writes nothing, so it is not kept as the last run.

The response counts the employments that were started and the assignments that were added, moved, changed, and ended.
It lists every value that matched nothing, with the user, the field, and the reason.
The reason is `unknown` when nothing has the value, or when it names a deactivated job title that the assignment does not have. It is `ambiguous` when more than one org unit has the name.

A user that the sync cannot handle does not stop the other users.
The response lists each such user in `failed`, with the error code that an error response would have.
The code is `internal` when the error has no code. The log of the server then has the cause.
Nothing of a failed user is written, and the next run tries the user again.
For example, a user who needs a new employment fails with `employment.employer_required` when the realm has several legal entities and no `fuhito.sync.employer`.

A failed user is not in `unmatched`. An entry there names the field and the value to fix.
A failure has neither, and its cause can be outside the attributes of the user, as in the example above.

Every write of the sync is in the change log, like any other write.

A run does its work in pieces of 100 users, and each piece is one transaction. One transaction for a large realm would run into the transaction timeout of Keycloak.
So a run that stops in the middle keeps the pieces that it finished, with their entries in the change log. The next run does the rest.
A dry run works in the same pieces, and undoes each piece at its end.
Each piece keeps the other writes of the realm waiting until it ends, so they wait for one piece at a time and not for the whole run.

When a piece fails, each of its users runs again in a transaction of its own.
An error of the database can spoil the transaction of the whole piece, so the users that did not fail run again too.
Only the users that fail on their own are left out. A piece that does not fail costs no extra transaction.

The run as a whole still fails when the realm did not turn the sync on, or when `fuhito.sync.employer` names no org unit.
It also fails with `realm.not_found` and the status 404 when Keycloak removes the realm during the run. No user runs again on its own then.
Only one run goes on for a realm at a time, on all nodes of a cluster. A second call fails with `sync.running` and the status 409. A dry run counts as a run.

A run reads the attributes of all users from the tables of Keycloak in one query.
A user that only a user storage provider holds is not in those tables. A user that Keycloak imported, for example from LDAP, is in them.
A user is outside the tables when a custom user storage provider keeps the users in another database, or when an LDAP federation has "Import Users" off.
The sync does not see such a user. It reports no error, and the user gets no assignment.
An attribute that only LDAP holds is not in the tables either. A mapper of the federation must import it.

A change that the sync makes and takes back on the same day leaves no trace.
An assignment that started today is removed, not ended, because it never held.

The user attributes must reach Keycloak first.
With the default user profile, Keycloak drops attributes that the profile does not define.
Define the attributes in the user profile of the realm, as the SCIM documentation of Keycloak describes.

### Settings of a realm

| Realm attribute | Meaning | Default |
|---|---|---|
| `fuhito.sync.enabled` | `true` turns the sync on | off |
| `fuhito.sync.attribute.department` | The user attribute with the department | `department` |
| `fuhito.sync.attribute.title` | The user attribute with the title | `title` |
| `fuhito.sync.attribute.manager` | The user attribute with the user ID of the manager | `manager` |
| `fuhito.sync.attribute.employeeNumber` | The user attribute with the staff number | `employeeNumber` |
| `fuhito.sync.employer` | The code of the org unit that employs the people of the sync. It is a legal entity. A realm with several legal entities needs it. | The only legal entity of the realm |

## Open points

- The dates are the dates of the sync, not the dates of the real change. An identity provider does not send the real date.
- A daily sync is a call. The timer of the projection runs a rebuild once a day. The sync could use the same timer.

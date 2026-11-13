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

1. Match the department to an org unit that exists today. Try the code first, then the name.
2. Match the title to a job title. Try the code first, then the name.
3. Give the user an employment that starts today, if the user has none today.
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
See "What today is" in [keycloak-integration.md](keycloak-integration.md).

The sync never touches an assignment that it does not own.
So an administrator can add further assignments by hand, and the sync leaves them alone.

A department or a title that matches nothing is reported, and that user is skipped.
The sync does not create org units or job titles. A typing mistake in the identity provider must not grow the tree.

### How to run it

`POST /attribute-sync/run` runs the sync. `?dryRun=true` reports what it would do and writes nothing.

The response counts the employments that were started and the assignments that were added, moved, changed, and ended.
It lists every value that matched nothing, with the user, the field, and the reason.
The reason is `unknown` when nothing has the value, and `ambiguous` when more than one org unit has the name.

Every write of the sync is in the change log, like any other write.

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

## Open points

- The dates are the dates of the sync, not the dates of the real change. An identity provider does not send the real date.
- A daily sync and a daily rebuild of the projection are two calls. An issue tracks a timer for the projection. The sync could use the same timer.

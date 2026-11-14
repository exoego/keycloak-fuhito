# Personal data

This document explains what the extension stores about a person, and the three ways to handle the data of a person who left.

It describes what the software does. It is not legal advice.
Which way a law requires depends on the law, the country, and the reason for keeping the data.

## What is stored about a person

| Data | Where |
|---|---|
| The Keycloak user ID | Employments, qualifications, reporting lines, change orders, and the change log |
| The name, the names in other languages, and the phonetic name | The person record |
| The staff number | Employments |
| The number of a license or certificate | Qualifications |
| The ID in another system | The external ID of people, employments, assignments, and qualifications |
| Free values that the realm defines | The attributes of people, employment terms, assignments, and qualifications |
| Where and when the person worked, with job title and occupation | Employments, assignments, and reporting lines |
| A copy of all of the above after each write | The change log |

The email address and the credentials of a person are in Keycloak, not in this extension.
The name of a person with a Keycloak user is in Keycloak. The person record can also have one.
A person with no Keycloak user has the name only in the person record.

## Three ways

| | Keep | Mask | Erase |
|---|---|---|---|
| How | Remove the user from Keycloak, or do nothing | `POST /people/{id}/mask` | `DELETE /people/{id}/data` |
| Records and their IDs | Stay | Stay | Gone |
| Org units, job titles, occupations, dates | Stay | Stay | Gone |
| The names in the person record | Stay | Cleared | Gone, with the person record |
| Staff number, license numbers, external IDs, free attributes | Stay | Cleared | Gone |
| The same fields in the change log | Stay | Cleared | The entries are gone |
| Can be undone | Yes | No | No |
| Applications that refer to a record by ID | Find it | Find it | Find nothing |

### Keep

Removing a user from Keycloak keeps everything.
It ends the open employment on that day, so the person is no longer a current member.
See [keycloak-integration.md](keycloak-integration.md).

### Mask

Masking removes what identifies the person directly, and keeps the structure of the history.

- It works only for a person with no employment that is open today or starts later.
- It works for a person with a Keycloak user, for one whose user was removed, and for one who never had a user.
- `POST /users/{userId}/mask` masks the person of a user.
- A person who is still linked to a user keeps the name that Keycloak has. Remove the user to remove that name.
- The history of the person has `maskedAt` afterwards.
- The change log records who masked which person ID.
- Masking again keeps the first time, and clears what was added since.

### Erase

Erasing removes the person record, and every row and every change log entry about the person.
The change log keeps one entry that says who erased the data of which person ID.

A system that follows the change log may hold copies of the cleared or removed data.
The mask entry and the erase entry tell it which person ID to clean up. See [history.md](history.md).

## What masking does not do

Masking is not always enough to make data anonymous. Check these points before relying on it.

- **A small org unit can identify a person by structure alone.** "The head of ward 3 from 2020 to 2024" is one person, with or without a staff number.
- **The user ID stays.** An application that stores the same ID next to a name can still tell who it was. Masking in the extension does not reach that application.
- **A user ID from a user federation can hold a username.** The ID of a user that Keycloak made itself is random. The ID of a federated user has the form `f:{provider}:{external id}`.
- **Names of org units and reference items are not masked.** A unit that is named after a person keeps that name.
- **Qualifications keep their kind and their dates.** A rare kind of qualification can point to one person.

When these points matter, erase the data, or remove the link in the other application too.

## Notes on laws

These notes explain why the three ways exist. They do not replace a check by someone who knows the law.

- Some laws require an employer to keep records of a worker for a number of years after the worker left. Erasing at once can be wrong.
- Many privacy laws treat data that can no longer be linked to a person as outside their scope. Data that is only hidden behind an ID that someone can still resolve usually stays inside.
- Many privacy laws ask to keep no more data than the purpose needs. Masking after the person left removes the fields that the history does not need.

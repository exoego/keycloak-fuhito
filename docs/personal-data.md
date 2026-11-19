# Personal data

This document explains what the extension stores about a person, and the three ways to handle the data of a person who left.

It describes what the software does. It is not legal advice.
Which way a law requires depends on the law, the country, and the reason for keeping the data.

## What is stored about a person

| Data | Where |
|---|---|
| The Keycloak user ID | Employments, qualifications, reporting lines, change orders, and the change log |
| The names and the phonetic names in each language | The person record |
| The staff number | Employments |
| The number of a license or certificate | Qualifications |
| The ID in another system | The IDs that systems have for people, employments, assignments, qualifications, reporting lines, and roles over org units |
| Why the person was on leave | Employment terms |
| Free values that the realm defines | The attributes of people, employment terms, assignments, and qualifications |
| Where and when the person worked, with job title and occupation | Employments, assignments, reporting lines, and roles over org units |

The change log keeps who wrote, when, and the IDs of what was written. It keeps no other data about a person.

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
| Staff number, license numbers, the IDs in other systems, leave reasons, free attributes | Stay | Cleared | Gone |
| The entries of the change log, which hold only IDs | Stay | Stay | Stay |
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
- A person who is still linked to a user keeps the name that Keycloak has. The responses still show it.
- `clearKeycloakName=true` on the request also empties the first and the last name of that user. The username, the email, and the attributes of the user stay.
- A user from a read-only user store cannot be changed. The mask then fails with `person.keycloak_name_read_only` and writes nothing. Mask without the parameter, and remove the name in the store that owns the user.
- Keycloak may ask a user with no name to fill the profile at the next login.
- A leave reason such as sick leave tells of the health of the person. So the mask clears it, and keeps the status.
- The history of the person has `maskedAt` afterwards.
- The change log records who masked which person ID.
- Masking again keeps the first time, and clears what was added since.

### Erase

Erasing removes the person record, and every row about the person.
The change log keeps its entries about the person, because they hold only IDs. It gets one more entry that says who erased the data of which person ID.
The realm also keeps the person ID and the time of the erase, and nothing else. A restore uses them to erase the person again.

An export of the realm has the data of people as it is stored. An export taken before a mask or an erase still has what they removed. A restore of such an export erases those people again after the load, so erased data does not come back. A mask comes back as the export has it, because a masked person stays in use. A person masked after the export gets the data of the export back, and needs a mask again. Copies of the export file itself are not changed. Remove old export files that you no longer need.

A system that follows the change log may hold copies of the cleared or removed data.
The mask entry and the erase entry tell it which person ID to clean up. See [history.md](history.md).

### Other copies

- Keycloak keeps admin events with the representation of each write when the realm asks for it. A mask and an erase do not change them. Use the settings of admin events in Keycloak to limit what they keep and for how long.
- The last run of the sync keeps the users and the values that matched nothing, until the next run replaces it.
- The projection records the user ID of each member that it added to a Keycloak organization. A mask and an erase do not remove the record. The next rebuild takes a user with no assignment out of the organization and removes the record.

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

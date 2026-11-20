# Import

This document explains how to load data in bulk.
All paths are below `/admin/realms/{realm}/fuhito`.

## The idea

An import describes the state from an effective date.

- A new thing starts on that date.
- A change to an existing thing takes effect on that date.
- A row that matches what is stored does nothing. So the same file can be sent twice.

All rows are written, or none.
A dry run does every check and writes nothing.
Every bad row is reported, not only the first one.

## How rows are matched

| List | Matched by |
|---|---|
| Kinds of reference data | ID in the system of the source, or ID |
| Attribute definitions | ID in the system of the source, or ID |
| Hierarchies | ID in the system of the source, or ID |
| Reference items | ID in the system of the source, or ID |
| Org units | ID in the system of the source, or ID |
| Parents in hierarchies | Hierarchy and org unit |
| People | ID in the system of the source, or ID |
| Employments | ID in the system of the source, or ID |
| Assignments | ID in the system of the source, or ID |
| Reporting lines | ID in the system of the source, or ID |
| Roles over org units | ID in the system of the source, or ID |
| Qualifications | ID in the system of the source, or ID |
| IDs of other systems | Type of resource, resource, and source |
| Change orders | ID in the system of the source, or ID |

The source is the name of the system or the sheet that the rows come from.
It keeps the external IDs of two systems apart. The default is `import`.
A row matches a stored thing that has the external ID of the row in the system of the source, whichever system made the thing. So a file of the payroll system finds a person that the HR system made, once the person has an ID of the payroll system. `PUT /external-ids` and the list of IDs of other systems give those IDs.
A row that matches nothing makes a new thing. Its `source` is the system of the row, and its external ID is stored as the ID of that system.
References such as `parentExternalId` and `personExternalId` are matched the same way, with the source of the row.
A row can name a stored thing by `id` in place of the external ID, also when the thing has no external ID. An external ID beside it must be one of that thing in the system of the source, or the row fails with `import.id_mismatch`. An `id` that names nothing fails with `import.id_unknown`. A row with neither fails with `invalid.required`. A CSV export has `id` in every row. A new thing has no ID.

A row never finds a stored thing by its values, such as a code or the manager of a line. A system that has no ID for a thing derives one by its own rule before it sends the file, for example from the kind and the code of a reference item. Codes and numbers stay values that no other thing has, so a new row with a taken code fails.

Some values tell what a thing is, so they never change. A row of a stored thing that leaves one out keeps it. Another value fails with `import.value_fixed`. Null fails with `invalid.required`, or takes the default of a value that has one, such as the kind of a reporting line. A different thing needs a different external ID. These values are the following.

- The code of a kind of reference data.
- The target and the name of an attribute definition.
- The code of a hierarchy.
- The kind and the code of a reference item.
- The assignment, the manager, and the kind of a reporting line.
- The employment, the org unit, the role, and `includesDescendants` of a role over an org unit.
- The start of an org unit, an employment, an assignment, a reporting line, and a role over an org unit, in `validFrom`.

Parents in hierarchies and the IDs of other systems have no ID of their own, so they keep their keys. How a row names another thing does not change either. For example, an assignment names its job title by code and its employment by `employmentExternalId`.

## People

The list `people` loads people, also those with no Keycloak user, such as staff who left long ago or never log in. It comes before the employments.

- A row has `primaryLanguage`, `names`, and the attributes, as `POST /people` has them. It can link a user with `username` or `userId`. A row with both fails with `import.user_twice`. A row that leaves out both keeps the link that is stored, and null for them removes it. A row that links a user can leave out the names.
- A row never finds a person by its user. A row with an external ID that names nothing makes a new person. If its user has a person already, the row fails with `import.user_taken`, and the error names that person by `personId`. A row of that person names it by `id` or by an external ID that it has. The list `externalIds` gives it the ID of another system.
- A row of a stored person that names another user links the person to that user, as `PUT /people/{id}` does. A user who has another person fails with `person.user_taken`.
- A row of an employment can name its person by `personExternalId`, with the same source. A person with no user is named this way. With a user as well, the user must be the one of that person.
- A row for a stored employment may leave out the person and the user. The employment keeps its person. Null for them fails with `invalid.required`.

## JSON

`POST /imports` takes one document with several lists.
The lists are applied in the order change orders, kinds of reference data, attribute definitions, reference items, hierarchies, org units, parents in hierarchies, people, qualifications, employments, roles over org units, assignments, reporting lines, IDs of other systems.

```json
{
  "effectiveDate": "2026-04-01",
  "dryRun": false,
  "source": "sheet",
  "refItems": [
    {"externalId": "OCCUPATION.nurse", "kind": "OCCUPATION", "code": "nurse", "primaryLanguage": "en",
     "names": {"en": {"name": "Nurse"}}}
  ],
  "orgUnits": [
    {"externalId": "H", "primaryLanguage": "en", "names": {"en": {"name": "X Hospital"}},
     "validFrom": "1998-04-01"},
    {"externalId": "W1", "primaryLanguage": "en", "names": {"en": {"name": "Ward 1"}}, "parentExternalId": "H"}
  ],
  "employments": [
    {"externalId": "E1", "username": "nurse", "staffNumber": "N-001"}
  ],
  "assignments": [
    {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W1",
     "occupation": "nurse", "primary": true}
  ]
}
```

### A key that a row leaves out

| In a row | Meaning |
|---|---|
| The key is missing | Keep the stored value |
| `null` | Clear the stored value |
| `""` or a text of only spaces | Fails, for every field |
| An empty array `[]` | Fails. Use `null` to clear a list |

- An object such as `attributes` or `names` merges by key. A missing key keeps the stored value, and a key with `null` clears it. For example, `"names": {"en": null}` removes the names in English, as `name:en` with `#CLEAR` does in CSV. The names of one language merge by field too, so `"names": {"ja": {"shortName": null}}` removes the short name and keeps the name.
- Two keys of `names` with one language tag in standard form, such as `ja` and `JA`, fail the row with `invalid.duplicate_language`, as in a REST write. This holds also when one of them is `null`, such as `{"ja": {"name": "営業部"}, "JA": null}`.
- `null` for a value that a thing must have, such as a name of the primary language or the start of a stored thing, fails with `invalid.required`, as a missing value of a new thing does. A value with a default, such as `status`, `active`, `head`, or `displayOrder`, takes the default.
- A new thing has nothing stored, so a missing key and `null` both mean no value, and required values must be there.
- An empty text, a text of only spaces, and an empty array fail with `invalid.blank`, which names the field, for example `names.en.shortName` or `attributes.beds`. This holds for every field, also for a text in the attributes. A REST write rejects an empty text and an empty array with the same error.
- A text with NUL (U+0000) or with half of a surrogate pair, such as `"\ud800"`, fails with `invalid.character`. The database cannot store such a text as it is sent. The error names the field and the `codePoint`, for example `U+0000`. This holds for every field, also for a code, a staff number, and a text or a key in the attributes. A REST write rejects such a text with the same error. Other characters, such as control characters, emoji, and combining marks, are stored as sent.
- A text longer than its field fails, as in a REST write. The length counts characters, so an emoji is one character. A text with no other rule, such as the `externalReference` of a change order, fails with `invalid.too_long`, which names the field and the `max`.
- A CSV file follows the same rule. A blank cell is a missing key, and `#CLEAR` is `null`. A cell with NUL fails with `invalid.character`, which names the column.
- An object gives each key once. A document that gives one key twice in one object fails with 400 and `invalid.duplicate_key` before any row is read. The error is not in the report of the rows. It names the key with its path, such as `orgUnits[1].names.ja` for the second row of `orgUnits`, or `effectiveDate`. No value is dropped. This holds for every object, also `attributes` and `names`, and for every REST write. A CSV file with a column twice fails with `csv.bad_column`.

### Rules for lists

- A row of a reference item has `primaryLanguage` and `names`, as `POST /ref-items` has them. It can have `validFrom`, the first date of its names. A new item without it has names that hold for every date. A stored item without it gets the names of the row from the effective date, and keeps its old names for the dates before. Null is the same as no `validFrom`, as it names a date and no stored value. A row compares with the names of its date, so the same row changes nothing the second time. Two rows of one item with two dates load two periods of names, for example the names of a job title from a system that kept them.
- The list `refKinds` adds kinds of reference data to the realm. A row has `code`, `primaryLanguage`, and `names`, as `POST /ref-kinds` has them. A row of a stored kind changes its names and keeps its code. A fixed kind such as `UNIT_TYPE` is in every realm and cannot be a row.
- The list `attributeDefinitions` defines free attributes. A row has `target`, `name`, `type`, `refKind`, `multiple`, and `required`. A row of a stored definition changes the type, the kind, `multiple`, and `required`, as `PUT /attribute-definitions/{id}` does. The target and the name stay. Stored values stay as they are. The definitions come before the reference items, so that a row of a reference item can set an attribute that the same document defines.
- The list `hierarchies` adds hierarchies. A row has `code` and `name`. A row of a stored hierarchy changes its name and keeps its code. A row names the default hierarchy by its `id`, which the CSV export has, or by an ID that the list of IDs of other systems gave it. The code `default` in a row of a new hierarchy fails, as the code is taken.
- Rows of org units can come in any order. A parent is applied before its children.
- A parent with no external ID of the same source can be named by `parentId`, the ID of the unit. A row gives `parentExternalId` or `parentId`, not both.
- The list `orgUnitParents` gives the parent of an org unit in a hierarchy from the effective date. A row has `hierarchy`, the unit by `orgUnitExternalId` or `orgUnitId`, and the parent by `parentExternalId` or `parentId`. A row with null for the parent takes the unit out of the hierarchy, and a row that leaves out the parent keeps it. The parent in the default hierarchy is in the row of the org unit, but a row of this list can name the default hierarchy too.
- A row of a stored org unit can name it by `id`, also when the unit has no external ID. An external ID beside it must be the one of that unit.
- A row of a stored employment or assignment can name it by `id` the same way.
- A row of an employment can name its person by `personId` and its employer by `employerId`. A row gives the external ID or the ID, not both.
- A row of an assignment can name its employment by `employmentId` and its org unit by `orgUnitId` the same way. A row of a stored assignment that leaves out both keeps the stored ones.
- The list `reportingLines` gives the manager of an assignment. A row names the line by `externalId` or `id`, the assignment by `assignmentExternalId` or `assignmentId`, and the manager by `managerPersonExternalId`, `managerPersonId`, or `managerUsername`. `kind` is `SOLID` or `DOTTED`, and the default is `SOLID`. The assignment, the manager, the kind, and the start of a stored line do not change. A new solid line fails with `reporting_line.solid_exists` while the assignment has a solid line on the same dates, as `POST /reporting-lines` does. The error names that line. So a change of the manager is two rows. One row ends the stored line with `validTo`, and one row adds the new line with another external ID. The two rows can come in any order. With no `validTo`, a line lasts as long as the assignment.
- The list `unitRoles` gives the roles that employments hold over org units. A row names the role by `externalId` or `id`, the employment by `employmentExternalId` or `employmentId`, the unit by `orgUnitExternalId` or `orgUnitId`, and the role by the code of a reference item of the kind `UNIT_ROLE`. The employment, the unit, the role, `includesDescendants`, and the start of a stored role do not change. A new role fails with `unit_role.overlap` while the employment holds the same role over the unit on the same dates, as `POST /unit-roles` does. The error names that role. So a change of `includesDescendants` is two rows. One row ends the stored role with `validTo`, and one row adds the new role with another external ID. The two rows can come in any order. With no `validTo`, a role lasts as long as the employment.
- The list `qualifications` gives qualifications such as licenses. A row names the person by `personExternalId`, `personId`, or `username`. A row of a stored qualification may leave out the person. A row of a stored qualification changes the kind, the number, the dates, and the attributes that it gives. Null for `validTo` removes the end. A row of a stored qualification with no `validFrom` keeps the stored start, as a blank cell of a CSV file does. A new qualification with no `validFrom` starts on the effective date.
- The list `externalIds` gives the IDs that systems have for resources, as `PUT /external-ids` does. A row has `resourceType`, `resourceId`, `source`, and `externalId`. `resourceType` is any type that `PUT /external-ids` takes. Another external ID replaces the stored one, and null removes it. A row that leaves out `externalId` keeps the stored one. A resource keeps its `source` when the ID of that system is removed.
- The list `changeOrders` gives change orders. A row has the values of `POST /change-orders`. It can name the approver and the requester by `approverUsername` and `requesterUsername` too. A row of a stored order changes the values that it gives, the number too. A new order with a taken number fails with `change_order.number_taken`. A row of a new order with no `effectiveDate` takes the effective date of the import. A row of a stored order with no `effectiveDate` keeps the stored date, as a blank cell of a CSV file does. Null gives the effective date of the import. The announcement is a fact of its own, so it is not part of a row. Record it with `POST /change-orders/{id}/announce`.
- A new row can have its own `validFrom`. A row of a stored org unit, employment, assignment, reporting line, or role over an org unit can leave it out. Otherwise it must be the stored start, or the row fails with `import.value_fixed`. Null fails with `invalid.required`. The stored start is the first date of the whole thing. For an org unit it is the first date of its first details. For an employment it is not the start of the terms of the date, and for an assignment it is not the start of the details of the date. The CSV exports of employments, assignments, reporting lines, and roles over org units have the stored start in `validFrom`. The file of org units has no `validFrom`. So an exported file loads again.
- A row with `validTo` ends the thing on that date. An employment can also carry `endReason`. Null removes the end of a stored org unit, employment, or assignment, as `DELETE /org-units/{id}/end` and the others do. An assignment then ends with its employment. A reporting line or a role over an org unit can only end earlier. With null, it must already end with its assignment or its employment, or the row fails with `import.end_kept`.
- Only an employment with an end has an end reason. A row that gives `endReason` and leaves the employment with no end fails with `employment.end_reason_without_end`, which names the `endReason`. This holds for a new employment with no `validTo`, for null in `validTo`, and for a stored employment with no end. A CSV row fails the same way, with `#CLEAR` in `validTo` or with a blank cell there and no stored end. Null in `validTo` with no `endReason` removes the stored end reason with the end, as `DELETE /employments/{id}/end` does.
- A row of an org unit can link the unit to a Keycloak organization by `organizationAlias`. A row that leaves it out keeps the link, and null removes it. As `PUT /org-units/{id}/organization` does, the import asks for the permission to manage the old and the new organization. An unknown alias fails with `import.organization_unknown`.
- A row of an org unit can set `timeZone` and `dayStart`, as `PUT /org-units/{id}/time-settings` does. A row that leaves one out keeps it, and null removes it.
- A row of an org unit or of an assignment can carry `workLocation` and `costCenter`, the codes of items of the kinds `WORK_LOCATION` and `COST_CENTER`. They are details like the others, so a row that leaves one out keeps it, and null clears it. The record then takes the one of the unit or of a unit above it. An unknown code fails with `ref_item.unknown`.
- A row of an org unit with `validTo` can name the units that follow it from that date, by `successorExternalIds` with the same source or by `successorIds`. The end and the successors are one write, as in the step `succeed` of a reorganization. One successor is a succession, and several are a split. A successor can come later in the same document. A unit that ended on the same date with the same successors stays as it is. Other successors fail with `import.successors_differ`, because a succession does not change.
- The same document can be sent again, for example after a timeout. A thing that ended by the effective date only compares its end. A thing that starts after the effective date must match what is stored on its first date, or the row fails with `not_valid_on`.
- A new employment names its employer by `employerExternalId`. It is the external ID of an org unit that is a legal entity. A row can leave it out when the realm has exactly one legal entity.
- A row of an employment can name an external employer, such as a staffing agency, by `externalEmployer`. It is the code of a reference item of the kind `EXTERNAL_EMPLOYER`. The CSV header is `外部の雇用主`. A row gives the employer or the external employer, not both, or it fails with `employment.employer_twice`. A new employment needs an active item. An unknown code fails with `employment.external_employer_unknown`, and an inactive item with `employment.external_employer_inactive`. The list `refItems` comes before the employments, so one document can add the agency and the people it employs.
- The employer of a stored employment never changes. A row that names another employer or another external employer is rejected with `import.employer_mismatch`. A row of a stored employment may name an external employer that is no longer active.
- After a transfer between employers, a row of the new employment can name the one it continues by `continuesEmploymentExternalId` or `continuesEmploymentId`. A row of a new assignment can name the assignment it continues by `continuesAssignmentExternalId` or `continuesAssignmentId`. Both must be of the same person. The link is set when the row makes the new thing. A stored one with another link fails with `import.continues_mismatch`.
- The one that a new employment or assignment continues must end on the start of the new one or before. A gap between the two is fine. Otherwise the row of the new one fails with `employment.continues_holds` or `assignment.continues_holds`, and the error names the one it continues. An assignment ends on its own end or on the end of its employment. So a file of a transfer ends the old employment with `validTo` on the start of the new one. The row that ends it and the row of the new one can come in any order. A row that sets the end of an employment or an assignment that another one continues fails the same way when the end comes after the start of the other one. So a later file cannot open the old one again.
- A row of a unit type can carry `legalEntity`. The CSV header is `法人`. A row that leaves it out keeps the mark of a stored item.
- A row of an assignment kind can carry `actingHead`. The CSV header is `代行`. A row that leaves it out keeps the mark of a stored item.
- A row of a unit type can carry `lateral`. The CSV header is `横断`. A row that leaves it out keeps the mark of a stored item.
- A row of a unit type can carry `rollup`. The CSV header is `集計`. A row that leaves it out keeps the mark of a stored item. One row cannot carry `rollup` with `lateral` or with `legalEntity`.
- A row of an org unit follows the rules of rollup units in "Rollup units" of the concepts. A row cannot give a rollup unit a parent, or put a unit below one. A row that gives a stored unit a rollup type and no parent takes the parent away first, then changes the details. The children of the unit must move away in earlier rows or documents.
- The user of an employment is named by `username` or by `userId`. A row with both fails with `import.user_twice`. The user must exist.
- The employment belongs to the person of that user. The import creates the person when the user has none.

## CSV

`POST /imports/csv/{list}` takes one file. The list is `ref-kinds`, `attribute-definitions`, `ref-items`, `hierarchies`, `org-units`, `org-unit-parents`, `people`, `employments`, `assignments`, `reporting-lines`, `unit-roles`, `qualifications`, `external-ids`, or `change-orders`.

`GET /imports/csv/org-units?asOf=` gives the org units of a date as such a file, for a change in a spreadsheet. Each row has the column `id`, so a unit with no external ID loads again too. The columns `source` and `externalId` hold the system that made the unit and the ID that the unit has there, as the other files of records do. The IDs of other systems are in the file of `external-ids`. The parent is in the column `parentId`. A unit that ends has `validTo`, and its successors are in `successorIds`. A linked unit has the alias of its Keycloak organization in `organizationAlias`. In a file, `#CLEAR` there removes the link. The file has `timeZone` and `dayStart` too, and `#CLEAR` removes one. With `header=ja`, the names of the columns are in Japanese. The file is UTF-8 with a byte order mark, which spreadsheets need to read it as UTF-8. The same file loaded on the same date changes nothing.

`GET /imports/csv/ref-kinds` gives the kinds that the realm added the same way. It takes no `asOf`. `GET /imports/csv/attribute-definitions` gives the definitions of attributes the same way. `GET /imports/csv/hierarchies` gives the hierarchies the same way, the default one too.
Kinds, definitions, hierarchies, reference items, reporting lines, roles over org units, and change orders keep no system that made them. So the columns `source` and `externalId` of their files hold the first of their IDs, by the name of the system. The row loads again by its `id` all the same.

`GET /imports/csv/ref-items?asOf=` gives the reference items of every kind the same way, the inactive ones too, with the names of the date. Each row has `id`, `source`, and `externalId`. A mark such as `legalEntity` is blank for a kind that cannot have it. The file has no `validFrom`, so a name changed in it loads as names from the effective date. A file to load can have the column `validFrom`, and blank cells of a row then keep the stored values of that date.

`GET /imports/csv/org-unit-parents?asOf=` gives the parents of the date in every hierarchy but the default one. It names the units by `orgUnitId` and `parentId`. In a file, blank cells of the parent keep the stored parent, and `#CLEAR` takes the unit out of the hierarchy.

`GET /imports/csv/people` gives the people the same way. A person has no date, so it takes no `asOf`. The file leaves out the link to a Keycloak user: a blank cell keeps the stored link, and the ID of a user who was removed from Keycloak would not load again.

`GET /imports/csv/employments?asOf=` gives the employments that hold on the date, with the terms of that date. Each row has `id`, and `continuesEmploymentId` for an employment that continues another. It names the person by `personId` and the employer by `employerId`, because many have no external ID of the source of the row. An employment with an external employer names it by its code in `externalEmployer`, and has no `employerId`. A row of an employment that starts later is not in the file.

`GET /imports/csv/assignments?asOf=` gives the assignments that hold on the date the same way, with the details of that date. It names the employment by `employmentId` and the org unit by `orgUnitId`. `validFrom` and `validTo` are the dates of the whole assignment, not of the details. An assignment that continues another has `continuesAssignmentId`.
The files of org units and of assignments have the work location and the cost center of the record itself, not the ones that apply. So a value that comes from a unit above is not copied into the rows.
`GET /imports/csv/reporting-lines?asOf=` gives the reporting lines that hold on the date. It names the assignment by `assignmentId` and the manager by `managerPersonId`.
`GET /imports/csv/unit-roles?asOf=` gives the roles that hold on the date. It names the employment by `employmentId` and the unit by `orgUnitId`.
`GET /imports/csv/qualifications` gives every qualification, also those that expired. With `asOf`, it gives only those that hold on the date.
`GET /imports/csv/external-ids` gives every ID of every system. The ID of the system that made a resource is in the file of the resource too. In a file, `#CLEAR` in `externalId` removes the stored ID.
`GET /imports/csv/change-orders` gives every change order, newest effective date first.
The query has `effectiveDate`, and optionally `dryRun` and `source`.
The content type is `text/csv`.

```sh
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/csv; charset=UTF-8" \
  --data-binary @org-units.csv \
  "$KEYCLOAK/admin/realms/$REALM/fuhito/imports/csv/org-units?effectiveDate=2026-04-01&dryRun=true"
```

### Rules for files

- The first row has the column names, in English or in Japanese. Columns can be in any order.
- A column that is not needed can be left out.
- The file is UTF-8, with or without a byte order mark. For another encoding, name it in the `charset` of the content type, for example `text/csv; charset=Shift_JIS`. fuhito does not guess the encoding.
- A file that is not valid in its charset fails with `csv.bad_encoding`, and nothing is written. The error has the charset, and the line, the offset, and the hex of the first bad bytes. The offset is the position in the file, counted from 0. A file that a spreadsheet saved in Shift_JIS fails in this way when the content type names no `charset`.
- Unless the charset is UTF-16 or UTF-32, a file that starts with the bytes `FF FE` or `FE FF`, or has a `00` byte on its first line, fails with `csv.bad_encoding` too. Such a file is most likely UTF-16. A `00` byte after the first line is NUL in a cell, which fails with `invalid.character` and names the column.
- An unknown charset fails with `csv.bad_charset`.
- A cell can be in double quotes. A quoted cell can hold commas and line breaks.
- A cell of a list separates its items with `|`, for example `W1|W2`. This is the rule for `successorExternalIds`, `successorIds`, and the columns of attributes with `multiple`, in a file to load and in every CSV export. A JSON import document keeps its arrays. In an item, `\|` is a `|` and `\\` is a `\`. A `\` before any other character fails with `csv.bad_escape`. For example, the two external IDs `100|2024` and `200|2024` are the cell `100\|2024|200\|2024`.
- An empty item fails with `csv.empty_item`, for example in `a||b`, `|a`, or `a|`. Spaces around an item are removed. A blank cell keeps the stored list, and `#CLEAR` removes it.
- A row with more cells than the header fails with `csv.too_many_cells`, unless the extra cells are empty. This is usually a comma that should have been in quotes.
- **A blank cell keeps the stored value.** A blank parent keeps the stored parent, also one with no external ID of the source.
- The cell `#CLEAR` removes the stored value.
- A file follows the rule of a JSON row.

| JSON row | CSV cell |
|---|---|
| The key is missing | A blank cell |
| `null` | `#CLEAR` |
| A value | The value |

- True is `true`, `1`, `yes`, `はい`, or `○`. False is `false`, `0`, `no`, `いいえ`, or `×`.
- A name in another language has its own column, for example `name:en` or `名称:en`.
- Two columns of one name in one language fail the file with `invalid.duplicate_language`, such as `name:ja` and `name:JA`, or `name:en-us` and `名称:en-US`. The error names the second column as the file writes it in `field`, and the language tag in standard form in `value`. Columns of different names may write one tag in another case, such as `name:en` and `shortName:EN`.
- In a file of org units, `name`, `shortName`, and `phoneticName` are the names of the primary language, which `primaryLanguage` names. A new unit needs `primaryLanguage`. `name:en`, `shortName:en`, and `phoneticName:en` are the names of one language. `name:en` with `#CLEAR` removes that language. A CSV export leaves the columns of a language empty for a unit whose primary language it is.
- A file of people has the names in the same way, with `primaryLanguage`, `name`, `phoneticName`, `name:en`, and `phoneticName:en`. A row with a name needs `primaryLanguage`, unless the person has a primary language already.
- A file of reference items has the names in the same way, with `primaryLanguage`, `name`, and `name:en`. A new item needs `primaryLanguage`. A row for a stored item keeps the primary language of the names of its date when the cell is blank.
- A file of kinds of reference data has the names in the same way, with `primaryLanguage`, `name`, and `name:en`. A new kind needs `primaryLanguage`. A row for a stored kind keeps its primary language when the cell is blank.
- A row gives each name of the primary language in one of two columns, the column with no language or the column of that language. A row that fills both fails with `invalid.duplicate_language`. For example, a row with `ja` in `primaryLanguage` fails when it has a cell in `name` and in `name:ja`. A row with a blank `primaryLanguage` takes the primary language that is stored. The error names the column of the language as the file writes it in `field`, such as `名称:JA`, and the language tag in standard form in `value`.
- `#CLEAR` counts as a cell there. So `#CLEAR` in one of the two columns fails with a value or `#CLEAR` in the other. A blank cell in one of them leaves the name to the other. For example, with `ja` as the primary language, `#CLEAR` in `shortName:ja` and a blank `shortName` remove the short name. Each name has its own two columns, so a row may fill `name` and `shortName:ja`. A CSV export leaves the column of the primary language empty, so it loads again.
- A row that removes a language with `#CLEAR` in `name:en` leaves the other names of that language blank, such as `shortName:en` and `phoneticName:en`. A row that fills one of them fails with `csv.clear_conflict`, in any order of the columns. `#CLEAR` counts as a cell there too. When the language is the primary one, `shortName` and `phoneticName` are names of it as well. For example, with `ja` as the primary language, `#CLEAR` in `name:ja` and a cell in `shortName` fail. The error names the column of the other name as the file writes it in `field`, such as `略称:EN`, and the language tag in standard form in `value`. A file of reference items or kinds has only `name`, so this cannot happen there.
- A column `attributes.<name>` sets an attribute that the realm defined for the resource of the list, for example `attributes.beds`. The cell is read by the type of the definition. The cell of a definition with `multiple` is a list, for example `night|icu`, and each item is read by the type of the definition. Other attributes keep what is stored. A column of an attribute with no definition is rejected.

### Columns

Kinds of reference data.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `code` | `コード` |
| `primaryLanguage` | `主言語` |
| `name` | `名称` |

Attribute definitions.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `target` | `対象` |
| `name` | `名称` |
| `type` | `型` |
| `refKind` | `参照の種類` |
| `multiple` | `複数` |
| `required` | `必須` |

Hierarchies.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `code` | `コード` |
| `name` | `名称` |

Reference items.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `kind` | `種類` |
| `code` | `コード` |
| `primaryLanguage` | `主言語` |
| `name` | `名称` |
| `displayOrder` | `表示順` |
| `active` | `有効` |
| `validFrom` | `開始日` |

Org units.

| English | Japanese |
|---|---|
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `code` | `コード` |
| `primaryLanguage` | `主言語` |
| `name` | `名称` |
| `shortName` | `略称` |
| `phoneticName` | `よみがな` |
| `unitType` | `組織種別` |
| `workLocation` | `勤務地` |
| `costCenter` | `コストセンター` |
| `parentExternalId` | `親の外部ID` |
| `parentId` | `親のID` |
| `id` | `ID` |
| `displayOrder` | `表示順` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |
| `successorExternalIds` | `後継の外部ID` |
| `successorIds` | `後継のID` |
| `organizationAlias` | `Organizationの別名` |
| `timeZone` | `タイムゾーン` |
| `dayStart` | `日の始まり` |

Parents in hierarchies.

| English | Japanese |
|---|---|
| `hierarchy` | `階層` |
| `source` | `ソース` |
| `orgUnitExternalId` | `組織の外部ID` |
| `orgUnitId` | `組織のID` |
| `parentExternalId` | `親の外部ID` |
| `parentId` | `親のID` |

Parents in hierarchies.

| English | Japanese |
|---|---|
| `hierarchy` | `階層` |
| `source` | `ソース` |
| `orgUnitExternalId` | `組織の外部ID` |
| `orgUnitId` | `組織のID` |
| `parentExternalId` | `親の外部ID` |
| `parentId` | `親のID` |

People.

| English | Japanese |
|---|---|
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `username` | `ユーザー名` |
| `userId` | `ユーザーID` |
| `primaryLanguage` | `主言語` |
| `name` | `名称` |
| `phoneticName` | `よみがな` |
| `id` | `ID` |

Employments.

| English | Japanese |
|---|---|
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `username` | `ユーザー名` |
| `userId` | `ユーザーID` |
| `staffNumber` | `職員番号` |
| `employmentType` | `雇用形態` |
| `grade` | `等級` |
| `status` | `状態` |
| `leaveReason` | `休職理由` |
| `workPercent` | `勤務割合` |
| `contractEndDate` | `契約期間満了日` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |
| `endReason` | `終了理由` |
| `employerExternalId` | `雇用主の外部ID` |
| `personExternalId` | `人の外部ID` |
| `id` | `ID` |
| `employerId` | `雇用主のID` |
| `externalEmployer` | `外部の雇用主` |
| `personId` | `人のID` |
| `continuesEmploymentExternalId` | `引き継ぎ元の雇用の外部ID` |
| `continuesEmploymentId` | `引き継ぎ元の雇用のID` |

Assignments.

| English | Japanese |
|---|---|
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `employmentExternalId` | `雇用の外部ID` |
| `orgUnitExternalId` | `組織の外部ID` |
| `jobTitle` | `役職` |
| `occupation` | `職種` |
| `assignmentKind` | `所属区分` |
| `workLocation` | `勤務地` |
| `costCenter` | `コストセンター` |
| `head` | `長` |
| `primary` | `主務` |
| `allocationPercent` | `配分割合` |
| `displayOrder` | `表示順` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |
| `id` | `ID` |
| `employmentId` | `雇用のID` |
| `orgUnitId` | `組織のID` |
| `continuesAssignmentExternalId` | `引き継ぎ元の所属の外部ID` |
| `continuesAssignmentId` | `引き継ぎ元の所属のID` |

Reporting lines.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `assignmentExternalId` | `所属の外部ID` |
| `assignmentId` | `所属のID` |
| `managerPersonExternalId` | `上司の人の外部ID` |
| `managerPersonId` | `上司の人のID` |
| `managerUsername` | `上司のユーザー名` |
| `kind` | `種類` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

Roles over org units.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `employmentExternalId` | `雇用の外部ID` |
| `employmentId` | `雇用のID` |
| `orgUnitExternalId` | `組織の外部ID` |
| `orgUnitId` | `組織のID` |
| `role` | `ロール` |
| `includesDescendants` | `下位の組織も含む` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

Qualifications.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `personExternalId` | `人の外部ID` |
| `personId` | `人のID` |
| `username` | `ユーザー名` |
| `kind` | `種類` |
| `number` | `番号` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

IDs of other systems.

| English | Japanese |
|---|---|
| `resourceType` | `リソースの種類` |
| `resourceId` | `リソースのID` |
| `source` | `ソース` |
| `externalId` | `外部ID` |

Change orders.

| English | Japanese |
|---|---|
| `id` | `ID` |
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `number` | `番号` |
| `kind` | `種類` |
| `title` | `件名` |
| `note` | `備考` |
| `orderDate` | `発令日` |
| `effectiveDate` | `発効日` |
| `approverUserId` | `承認者のユーザーID` |
| `approverUsername` | `承認者のユーザー名` |
| `requesterUserId` | `申請者のユーザーID` |
| `requesterUsername` | `申請者のユーザー名` |
| `externalReference` | `外部の参照` |

A row for a stored employment may leave out the user.
A row that leaves out `leaveReason` keeps the stored reason while the status stays `ON_LEAVE`.
A row for a stored assignment may leave out the employment and the org unit.

## The report

```json
{
  "dryRun": false,
  "applied": false,
  "counts": {"orgUnits": {"created": 1, "changed": 0, "unchanged": 0}},
  "errors": [
    {"list": "orgUnits", "row": 2, "line": 5, "error": "import.parent_unknown",
     "params": {"parentExternalId": "NOPE"}, "message": "..."}
  ]
}
```

- The status is 200 when the rows were written, and for a dry run with no errors.
- The status is 400 when a row failed. Nothing was written then.
- `row` counts the data rows from 0. `line` is the line of the CSV file, counted from 1.
- The language of `message` follows the `Accept-Language` header.

## Change orders

With `?changeOrder={id}`, every write of the import belongs to that change order.
Each write has its own entry in the change log.

## What an import does not do

- It does not end things that are missing from the file. A row must carry an end date.
- It does not create Keycloak users.
- It does not change the staff number of a stored employment.
- It does not remove an end date.
- It does not move an assignment to another org unit. End it and add a new one.
- One CSV file is one transaction. Four files are four transactions. Use JSON to load several lists at once.
- An import, also a dry run, keeps the other writes of the realm waiting until it ends. It waits for the writes that run when it starts. Reads go on.

## Export and restore

An export takes every stored row of a realm out, with every period. A restore loads it into a realm that has no data of the extension, or in place of the data of a realm.
This copies a realm to a test environment, moves it to another Keycloak, or brings it back after its data was lost or changed by mistake. It is not the import above, which describes one date.

- `GET /export/{list}` reads one list in pages, ordered by ID. Follow the `Link` header to the end. The lists are `ref-kinds`, `ref-items`, `attribute-definitions`, `hierarchies`, `org-units`, `people`, `employments`, `assignments`, `reporting-lines`, `unit-roles`, `qualifications`, `external-ids`, `change-orders`, and `erasures`.
- Put the lists together under their names in camel case, such as `refKinds`, with `"format": "fuhito.export.v1"`. `POST /restore` takes that document.
- A reference item has every period of its names in `versions`, each with `primaryLanguage` and `names`. A restore needs them.
- An employment has `employerId`, the ID of its legal entity, or `externalEmployerId`, the ID of the reference item of its external employer. A row with both fails with `employment.employer_twice`.
- A restore keeps every ID, so products that stored IDs find the same resources. It writes all or nothing. The default hierarchy of the export takes the place of the one of the empty realm, with its ID and its name.
- It fails with `restore.realm_not_empty` when the realm has any data of the extension. So a restore into the wrong realm does not remove its data.
- With `?replace=true`, the restore first deletes every row of the extension in the realm, in the same transaction. The realm is then as it was at the export. The change log stays, with the changes after the export. The runs of jobs and the records of the members that the projection added to organizations stay too. The next projection makes the Keycloak groups and organizations match. A replace asks for the permission to manage each organization that a unit was linked to before it.
- It checks the export before it writes. A row that names a row that the export does not have fails with 400 and `restore.reference_unknown`. So a restore never links to the rows of another realm.
- A row that is `null`, or a row with no value that its table needs, fails with 400 and `restore.bad_value` before anything is written. Such a value is one that an export always has, such as the ID of the row, `validFrom`, the person of an employment, or `attributes`, which is `{}` when it has none. The error names the `list`, the `id` of the row when it has one, and the `field`. A value that cannot be read also fails with `restore.bad_value`.
- A date must be one that a write takes, in the form `YYYY-MM-DD` from `0001-01-01` to `9999-12-31`. A time, such as `createdAt`, must be on such a date in UTC. Another date or time fails with 400 and `restore.bad_value` before anything is written. The error names the `list`, the `id` of the row, and the `field`.
- `validTo` is the first date after a period, so it must be after `validFrom`, as in a write. A row whose `validTo` is on or before its `validFrom` fails with 400 and `restore.bad_value` before anything is written. This holds for every row with a period. These are the names of reference items, the versions and the parents of org units, the employments and their terms, the rows of assignments, the reporting lines, the unit roles, and the qualifications. The error names the `list`, the `id` of the row, and the field `validTo`, for example `{"list": "employment-terms", "id": "t-1", "field": "validTo"}`. Names with no `validFrom` hold from the start, so any `validTo` fits them.
- An empty text, a text of only spaces, or an empty array fails with 400 and `invalid.blank`, as in a row of an import. This holds for every value of every row, also for the names and the attributes. The error names the `list` and the `id` of the row, and the `field`, for example `attributes.beds`. A row inside a row has its own list, such as `org-unit-versions` for a version of an org unit. A list of such rows, such as `parents`, may be empty. An export has no such value, because every write rejects it.
- A text with NUL (U+0000) or with half of a surrogate pair fails with 400 and `invalid.character`, as in a row of an import. This holds for every text of every row, also for the names, the attributes, and the keys of the attributes. The error names the `list`, the `id` of the row, the `field`, and the `codePoint`.
- A document that gives one key twice in one object fails with 400 and `invalid.duplicate_key`, as an import does. The error names the key with its path in the document, such as `orgUnits[0].versions[0].names.ja`. It has no `list` and no `id`, because the document is not read yet.
- A text longer than its column fails with 400 and `restore.bad_value` before anything is written. The length counts characters, as the column does. This holds for every text of every row, also for a row inside a row. A value that a write checks by a rule of its own must pass that rule too, and fails the same way. Such values are the codes, such as `code`, `source`, `staffNumber`, and the `number` and `kind` of a change order. They are also the names, such as the `name` of a hierarchy, the `title` of a change order, and the `number` of a qualification. The others are the `name` of an attribute definition, `externalId`, `workPercent`, `allocationPercent`, and the `timeZone` and `dayStart` of an org unit. The error names the `list`, the `id` of the row, and the `field`.
- Two rows of a list with the same ID fail with 400 and `restore.duplicate` before anything is written. This holds for the rows inside a row too, such as the versions of org units, and for both `assignmentId` and `rowId` of assignments. Two rows with the same unique key of the database fail the same way. These are the keys.

  | List | Key | `field` |
  |---|---|---|
  | `ref-kinds` | `code` | `code` |
  | `ref-items` | `kind` and `code` | `code` |
  | `attribute-definitions` | `target` and `name` | `name` |
  | `hierarchies` | `code` | `code` |
  | `org-units` | the Keycloak organization of `organizationAlias` | `organizationAlias` |
  | `org-unit-successions` | `successorId` in one org unit | `successorId` |
  | `people` | the Keycloak user of `username`. Keycloak finds a user by a username in any case, so `nurse` and `Nurse` name one user. | `username` |
  | `external-ids` | `resourceType`, `source`, and `externalId` | `externalId` |
  | `external-ids` | `resourceType`, `resourceId`, and `source` | `source` |
  | `change-orders` | `number` | `number` |
  | `erasures` | `personId` | `personId` |

  A key with no value, such as a change order with no `number`, is no key.
- Two rows that must not be valid on the same date fail with 400 and `restore.overlap` before anything is written. A write keeps these rules too.

  | List | Rows that must not share a date | `field` |
  |---|---|---|
  | `ref-item-names` | the names of one reference item. Names with no `validFrom` hold from the start. | `validFrom` |
  | `org-unit-versions` | the versions of one org unit | `validFrom` |
  | `org-unit-versions` | the versions of any org units with the same `code` | `code` |
  | `org-unit-parents` | the parents of one org unit in one `hierarchy` | `hierarchy` |
  | `employment-terms` | the terms of one employment | `validFrom` |
  | `assignments` | the rows of one assignment | `validFrom` |
  | `assignments` | the rows of one employment with `"primary": true` | `primary` |
  | `unit-roles` | the roles with the same `employmentId`, `orgUnitId`, and `roleId` | `roleId` |

  `validTo` is the first date after a period. So a row may start on the `validTo` of the row before it.
- Both errors name the `list`, the `id` of the row, the `field`, and the `other` row. The row is the later one in the export, or for an overlap the one that starts later. For example, `{"list": "org-unit-versions", "id": "v-2", "field": "validFrom", "other": "v-1"}`. An export never has such rows, because every write keeps the rules.
- An ID that a row of the database has already, in any realm, fails with 409 and `restore.id_taken`. This holds for the ID of an assignment too. This happens for a copy into a second realm of the same database while the first realm still has the rows.
- A row that breaks a rule of the database that the restore did not find fails with 409 and `restore.conflict`. The error names the rule in `constraint`, such as `PK_FUHITO_HIERARCHY`. This can happen when another write stores the same row at the same time. Nothing is written.
- Rows name Keycloak users by username. A restore fails with `restore.username_unknown` when the target realm has no user with that name. A person whose user Keycloak no longer had is exported with no username, and is restored with no link.
- Masked data stays masked, with the time of the mask. Erased data is not in an export.
- An erase keeps the ID of the person and the time, and nothing else. The list `erasures` has them. After the load, a restore erases again each person with such a record, also when the export was taken before the erase. So a restore never brings back erased data. A replace keeps these records of the realm.
- A mask comes back as the export has it. A mask keeps the person in use, unlike an erase. So a person who was masked after the export gets the data of the export back. Mask the person again when the data must stay hidden. A restore does not change the users of Keycloak, so a name that a mask cleared there stays cleared.
- An org unit names its Keycloak organization by alias, because the IDs of organizations differ between servers. A restore fails with `restore.organization_unknown` when the realm has no organization with the alias. It asks for the permission to manage each linked organization, as a link does.
- The change log, the admin events, and the records of the members that the projection added are not part of an export. The restore records one entry, `FUHITO_RESTORE`.
- The export needs the full read permission, because it has the data of people. The restore needs the permission to write.

Checked by `RealmExportIT.anExportLoadsIntoAnEmptyRealmAndGivesTheSameExportAgain`, `RestoreEmptyValuesIT.anExportOfCheckedDataRestoresUnchanged`, `RestoreEmptyValuesIT.anEmptyValueInAnyRowFailsTheRestoreAndNothingIsWritten`, `RestoreMissingValuesIT.aMissingRowOrValueFailsTheRestoreAndNothingIsWritten`, `RestoreLongValuesIT.aValueLongerThanItsColumnFailsTheRestoreAndNothingIsWritten`, `RestoreKeysIT`, `RestoreOverlapsIT`, and `RestorePeriodsIT`.

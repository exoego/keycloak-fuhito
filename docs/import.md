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
| Kinds of reference data | Code |
| Attribute definitions | Target and name |
| Hierarchies | Code |
| Reference items | Kind and code |
| Org units | ID in the system of the source, or ID |
| Parents in hierarchies | Hierarchy and org unit |
| People | ID in the system of the source, or ID |
| Employments | ID in the system of the source, or ID |
| Assignments | ID in the system of the source, or ID |
| Reporting lines | Assignment, manager, and kind, or ID |
| Roles over org units | Employment, org unit, and role, or ID |
| Qualifications | ID in the system of the source, or ID |
| IDs of other systems | Type of resource, resource, and source |
| Change orders | Number, or ID |

The source is the name of the system or the sheet that the rows come from.
It keeps the external IDs of two systems apart. The default is `import`.
A row matches a stored thing that has the external ID of the row in the system of the source, whichever system made the thing. So a file of the payroll system finds a person that the HR system made, once the person has an ID of the payroll system. `PUT /external-ids` and the list of IDs of other systems give those IDs.
A row that matches nothing makes a new thing. Its `source` is the system of the row, and its external ID is stored as the ID of that system.
References such as `parentExternalId` and `personExternalId` are matched the same way, with the source of the row.
A row can name a stored thing by `id` in place of the external ID, also when the thing has no external ID. An external ID beside it must be one of that thing in the system of the source. A CSV export has `id` in every row. A new thing has no ID.

## People

The list `people` loads people, also those with no Keycloak user, such as staff who left long ago or never log in. It comes before the employments.

- A row has the names, the phonetic name, and the attributes. It can link a user with `username` or `userId`. A row that names no user keeps the link that is stored.
- A row that links a user whose person the import made earlier gives that person the external ID of the row, as the ID of the system of the row. A user whose person has another ID of that system fails with `import.user_mismatch`.
- A row of an employment can name its person by `personExternalId`, with the same source. A person with no user is named this way. With a user as well, the user must be the one of that person.
- A row for a stored employment may name no person and no user. The employment keeps its person.

## JSON

`POST /imports` takes one document with several lists.
The lists are applied in the order change orders, kinds of reference data, attribute definitions, reference items, hierarchies, org units, parents in hierarchies, people, qualifications, employments, roles over org units, assignments, reporting lines, IDs of other systems.

```json
{
  "effectiveDate": "2026-04-01",
  "dryRun": false,
  "source": "sheet",
  "refItems": [
    {"kind": "OCCUPATION", "code": "nurse", "name": "Nurse"}
  ],
  "orgUnits": [
    {"externalId": "H", "name": "X Hospital", "validFrom": "1998-04-01"},
    {"externalId": "W1", "name": "Ward 1", "parentExternalId": "H"}
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

- A row of a reference item can have `validFrom`, the first date of its names. A new item without it has names that hold for every date. A stored item without it gets the names of the row from the effective date, and keeps its old names for the dates before. A row compares with the names of its date, so the same row changes nothing the second time. Two rows of one item with two dates load two periods of names, for example the names of a job title from a system that kept them.
- The list `refKinds` adds kinds of reference data to the realm. A row has `code`, `name`, and `names`. A row of a stored kind changes its names. A fixed kind such as `UNIT_TYPE` is in every realm and cannot be a row.
- The list `attributeDefinitions` defines free attributes. A row has `target`, `name`, `type`, `refKind`, `multiple`, and `required`. A row of a stored definition changes the type, the kind, `multiple`, and `required`, as `PUT /attribute-definitions/{id}` does. Stored values stay as they are. The definitions come before the reference items, so that a row of a reference item can set an attribute that the same document defines.
- The list `hierarchies` adds hierarchies. A row has `code` and `name`. A row of a stored hierarchy changes its name. The code `default` names the default hierarchy.
- Rows of org units can come in any order. A parent is applied before its children.
- A parent with no external ID of the same source can be named by `parentId`, the ID of the unit. A row gives `parentExternalId` or `parentId`, not both.
- The list `orgUnitParents` gives the parent of an org unit in a hierarchy from the effective date. A row has `hierarchy`, the unit by `orgUnitExternalId` or `orgUnitId`, and the parent by `parentExternalId` or `parentId`. A row with no parent takes the unit out of the hierarchy. The parent in the default hierarchy is in the row of the org unit, but a row of this list can name the default hierarchy too.
- A row of a stored org unit can name it by `id`, also when the unit has no external ID. An external ID beside it must be the one of that unit.
- A row of a stored employment or assignment can name it by `id` the same way.
- A row of an employment can name its person by `personId` and its employer by `employerId`. A row gives the external ID or the ID, not both.
- A row of an assignment can name its employment by `employmentId` and its org unit by `orgUnitId` the same way. A row of a stored assignment that names neither keeps the stored ones.
- The list `reportingLines` gives the manager of an assignment. A row names the assignment by `assignmentExternalId` or `assignmentId`, and the manager by `managerPersonExternalId`, `managerPersonId`, or `managerUsername`. `kind` is `SOLID` or `DOTTED`, and the default is `SOLID`. A new solid manager ends the solid line that the assignment has on the date, so a change of the manager is one row. With no `validTo`, a line lasts as long as the assignment.
- The list `unitRoles` gives the roles that employments hold over org units. A row names the employment by `employmentExternalId` or `employmentId`, the unit by `orgUnitExternalId` or `orgUnitId`, and the role by the code of a reference item of the kind `UNIT_ROLE`. A row that changes `includesDescendants` ends the stored role and starts a new one on the date. With no `validTo`, a role lasts as long as the employment.
- The list `qualifications` gives qualifications such as licenses. A row names the person by `personExternalId`, `personId`, or `username`. A row of a stored qualification may name nobody. A qualification has dates of its own, so a row replaces the kind, the number, the dates, and the attributes of a stored one, as `PUT /qualifications/{id}` does. A row with no `validFrom` starts on the effective date.
- The list `externalIds` gives the IDs that systems have for resources, as `PUT /external-ids` does. A row has `resourceType`, `resourceId`, `source`, and `externalId`. Another external ID replaces the stored one, and an empty text removes it. A resource keeps its `source` when the ID of that system is removed.
- The list `changeOrders` gives change orders. A row has the values of `POST /change-orders`. It can name the approver and the requester by `approverUsername` and `requesterUsername` too. A row of a stored order replaces its values, as `PUT /change-orders/{id}` does. A row with no `effectiveDate` takes the effective date of the import. The announcement is a fact of its own, so it is not part of a row. Record it with `POST /change-orders/{id}/announce`.
- A new row can have its own `validFrom`. An existing row ignores it.
- A row with `validTo` ends the thing on that date. An employment can also carry `endReason`.
- A row of an org unit can link the unit to a Keycloak organization by `organizationAlias`. A row that leaves it out keeps the link, and an empty text removes it. As `PUT /org-units/{id}/organization` does, the import asks for the permission to manage the old and the new organization. An unknown alias fails with `import.organization_unknown`.
- A row of an org unit can set `timeZone` and `dayStart`, as `PUT /org-units/{id}/time-settings` does. A row that leaves one out keeps it, and an empty text removes it.
- A row of an org unit or of an assignment can carry `workLocation` and `costCenter`, the codes of items of the kinds `WORK_LOCATION` and `COST_CENTER`. They are details like the others, so a row that leaves one out clears it. The record then takes the one of the unit or of a unit above it. An unknown code fails with `ref_item.unknown`.
- A row of an org unit with `validTo` can name the units that follow it from that date, by `successorExternalIds` with the same source or by `successorIds`. The end and the successors are one write, as in the step `succeed` of a reorganization. One successor is a succession, and several are a split. A successor can come later in the same document. A unit that ended on the same date with the same successors stays as it is. Other successors fail with `import.successors_differ`, because a succession does not change.
- A row replaces all details of an existing thing. A missing optional value clears it.
- The same document can be sent again, for example after a timeout. A thing that ended by the effective date only compares its end. A thing that starts after the effective date must match what is stored on its first date, or the row fails with `not_valid_on`.
- A new employment names its employer by `employerExternalId`. It is the external ID of an org unit that is a legal entity. A row can leave it out when the realm has exactly one legal entity.
- The employer of a stored employment never changes. A row that names another employer is rejected.
- After a transfer between employers, a row of the new employment can name the one it continues by `continuesEmploymentExternalId` or `continuesEmploymentId`. A row of a new assignment can name the assignment it continues by `continuesAssignmentExternalId` or `continuesAssignmentId`. Both must be of the same person. The link is set when the row makes the new thing. A stored one with another link fails with `import.continues_mismatch`.
- A row of a unit type can carry `legalEntity`. The CSV header is `法人`. A row that leaves it out keeps the mark of a stored item.
- A row of an assignment kind can carry `actingHead`. The CSV header is `代行`. A row that leaves it out keeps the mark of a stored item.
- A row of a unit type can carry `lateral`. The CSV header is `横断`. A row that leaves it out keeps the mark of a stored item.
- The user of an employment is named by `username` or by `userId`. The user must exist.
- The employment belongs to the person of that user. The import creates the person when the user has none.

## CSV

`POST /imports/csv/{list}` takes one file. The list is `ref-kinds`, `attribute-definitions`, `ref-items`, `hierarchies`, `org-units`, `org-unit-parents`, `people`, `employments`, `assignments`, `reporting-lines`, `unit-roles`, `qualifications`, `external-ids`, or `change-orders`.

`GET /imports/csv/org-units?asOf=` gives the org units of a date as such a file, for a change in a spreadsheet. Each row has the column `id`, so a unit with no external ID loads again too. The columns `source` and `externalId` hold the system that made the unit and the ID that the unit has there, as the other files of records do. The IDs of other systems are in the file of `external-ids`. The parent is in the column `parentId`. A unit that ends has `validTo`, and its successors are in `successorIds`. A linked unit has the alias of its Keycloak organization in `organizationAlias`. In a file, `#CLEAR` there removes the link. The file has `timeZone` and `dayStart` too, and `#CLEAR` removes one. With `header=ja`, the names of the columns are in Japanese. The file is UTF-8 with a byte order mark, which spreadsheets need to read it as UTF-8. The same file loaded on the same date changes nothing.

`GET /imports/csv/ref-kinds` gives the kinds that the realm added the same way. It takes no `asOf`. `GET /imports/csv/attribute-definitions` gives the definitions of attributes the same way. `GET /imports/csv/hierarchies` gives the hierarchies the same way, the default one too.

`GET /imports/csv/ref-items?asOf=` gives the reference items of every kind the same way, the inactive ones too, with the names of the date. An item is matched by kind and code, so the file has no `id`. A mark such as `legalEntity` is blank for a kind that cannot have it. The file has no `validFrom`, so a name changed in it loads as names from the effective date. A file to load can have the column `validFrom`, and blank cells of a row then keep the stored values of that date.

`GET /imports/csv/org-unit-parents?asOf=` gives the parents of the date in every hierarchy but the default one. It names the units by `orgUnitId` and `parentId`. In a file, blank cells of the parent keep the stored parent, and `#CLEAR` takes the unit out of the hierarchy.

`GET /imports/csv/people` gives the people the same way. A person has no date, so it takes no `asOf`. The file leaves out the link to a Keycloak user: a blank cell keeps the stored link, and the ID of a user who was removed from Keycloak would not load again.

`GET /imports/csv/employments?asOf=` gives the employments that hold on the date, with the terms of that date. Each row has `id`, and `continuesEmploymentId` for an employment that continues another. It names the person by `personId` and the employer by `employerId`, because many have no external ID of the source of the row. A row of an employment that starts later is not in the file.

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
- The file is UTF-8, with or without a byte order mark. For another encoding, name it in the content type, for example `text/csv; charset=Shift_JIS`.
- A cell can be in double quotes. A quoted cell can hold commas and line breaks.
- A cell of several successors separates them with `|`, for example `W1|W2`. An external ID that has `|` must be named by `successorIds`.
- A row with more cells than the header fails with `csv.too_many_cells`, unless the extra cells are empty. This is usually a comma that should have been in quotes.
- **A blank cell keeps the stored value.** This differs from JSON, where a missing value clears it. A blank parent keeps the stored parent, also one with no external ID of the source.
- The cell `#CLEAR` removes the stored value.
- True is `true`, `1`, `yes`, `はい`, or `○`. False is `false`, `0`, `no`, `いいえ`, or `×`.
- A name in another language has its own column, for example `name:en` or `名称:en`.
- A column `attributes.<name>` sets an attribute that the realm defined for the resource of the list, for example `attributes.beds`. The cell is read by the type of the definition. The cell of a definition with `multiple` is a JSON array, for example `["night","icu"]`, which the CSV writes as `"[""night"",""icu""]"`. Other attributes keep what is stored. A column of an attribute with no definition is rejected.

### Columns

Kinds of reference data.

| English | Japanese |
|---|---|
| `code` | `コード` |
| `name` | `名称` |

Attribute definitions.

| English | Japanese |
|---|---|
| `target` | `対象` |
| `name` | `名称` |
| `type` | `型` |
| `refKind` | `参照の種類` |
| `multiple` | `複数` |
| `required` | `必須` |

Hierarchies.

| English | Japanese |
|---|---|
| `code` | `コード` |
| `name` | `名称` |

Reference items.

| English | Japanese |
|---|---|
| `kind` | `種類` |
| `code` | `コード` |
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

## Export and restore

An export takes every stored row of a realm out, with every period. A restore loads it into a realm that has no data of the extension, or in place of the data of a realm.
This copies a realm to a test environment, moves it to another Keycloak, or brings it back after its data was lost or changed by mistake. It is not the import above, which describes one date.

- `GET /export/{list}` reads one list in pages, ordered by ID. Follow the `Link` header to the end. The lists are `ref-kinds`, `ref-items`, `attribute-definitions`, `hierarchies`, `org-units`, `people`, `employments`, `assignments`, `reporting-lines`, `unit-roles`, `qualifications`, `external-ids`, `change-orders`, and `erasures`.
- Put the lists together under their names in camel case, such as `refKinds`, with `"format": "fuhito.export.v1"`. `POST /restore` takes that document.
- A reference item has every period of its names in `versions`. An export from before names had periods has `name` and `names` in place of them, and a restore gives the item those names for every date.
- A restore keeps every ID, so products that stored IDs find the same resources. It writes all or nothing. The default hierarchy of the export takes the place of the one of the empty realm, with its ID and its name.
- It fails with `restore.realm_not_empty` when the realm has any data of the extension. So a restore into the wrong realm does not remove its data.
- With `?replace=true`, the restore first deletes every row of the extension in the realm, in the same transaction. The realm is then as it was at the export. The change log stays, with the changes after the export. The idempotency keys, the runs of jobs, and the records of the members that the projection added to organizations stay too. The next projection makes the Keycloak groups and organizations match. A replace asks for the permission to manage each organization that a unit was linked to before it.
- It checks the export before it writes. A row that names a row that the export does not have fails with 400 and `restore.reference_unknown`. So a restore never links to the rows of another realm.
- A value that is missing or cannot be read fails with 400 and `restore.bad_value`.
- An ID that a row of the database has already, in any realm, fails with 409 and `restore.id_taken`. This happens for a copy into a second realm of the same database while the first realm still has the rows.
- Rows name Keycloak users by username. A restore fails with `restore.username_unknown` when the target realm has no user with that name. A person whose user Keycloak no longer had is exported with no username, and is restored with no link.
- Masked data stays masked, with the time of the mask. Erased data is not in an export.
- An erase keeps the ID of the person and the time, and nothing else. The list `erasures` has them. After the load, a restore erases again each person with such a record, also when the export was taken before the erase. So a restore never brings back erased data. A replace keeps these records of the realm.
- A mask comes back as the export has it. A mask keeps the person in use, unlike an erase. So a person who was masked after the export gets the data of the export back. Mask the person again when the data must stay hidden. A restore does not change the users of Keycloak, so a name that a mask cleared there stays cleared.
- An org unit names its Keycloak organization by alias, because the IDs of organizations differ between servers. A restore fails with `restore.organization_unknown` when the realm has no organization with the alias. It asks for the permission to manage each linked organization, as a link does.
- The change log, the admin events, the idempotency keys, and the records of the members that the projection added are not part of an export. The restore records one entry, `FUHITO_RESTORE`.
- The export needs the full read permission, because it has the data of people. The restore needs the permission to write.

Checked by `RealmExportIT.anExportLoadsIntoAnEmptyRealmAndGivesTheSameExportAgain`.

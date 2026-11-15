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
| Reference items | Kind and code |
| Org units | Source and external ID |
| People | Source and external ID |
| Employments | Source and external ID |
| Assignments | Source and external ID |

The source is the name of the system or the sheet that the rows come from.
It keeps the external IDs of two systems apart. The default is `import`.
The import matches by the pair that a resource itself has. The IDs that `/external-ids` adds do not match rows.

## People

The list `people` loads people, also those with no Keycloak user, such as staff who left long ago or never log in. It comes before the employments.

- A row has the names, the phonetic name, and the attributes. It can link a user with `username` or `userId`. A row that names no user keeps the link that is stored.
- A row that links a user whose person the import made earlier, with no external ID, gives that person its external ID. A user whose person has another external ID fails with `import.user_mismatch`.
- A row of an employment can name its person by `personExternalId`, with the same source. A person with no user is named this way. With a user as well, the user must be the one of that person.
- A row for a stored employment may name no person and no user. The employment keeps its person.

## JSON

`POST /imports` takes one document with up to four lists.
The lists are applied in the order reference items, org units, employments, assignments.

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

- Rows of org units can come in any order. A parent is applied before its children.
- A parent with no external ID of the same source can be named by `parentId`, the ID of the unit. A row gives `parentExternalId` or `parentId`, not both.
- A row of a stored org unit can name it by `id`, also when the unit has no external ID. An external ID beside it must be the one of that unit.
- A new row can have its own `validFrom`. An existing row ignores it.
- A row with `validTo` ends the thing on that date. An employment can also carry `endReason`.
- A row replaces all details of an existing thing. A missing optional value clears it.
- The same document can be sent again, for example after a timeout. A thing that ended by the effective date only compares its end. A thing that starts after the effective date must match what is stored on its first date, or the row fails with `not_valid_on`.
- A new employment names its employer by `employerExternalId`. It is the external ID of an org unit that is a legal entity. A row can leave it out when the realm has exactly one legal entity.
- The employer of a stored employment never changes. A row that names another employer is rejected.
- A row of a unit type can carry `legalEntity`. The CSV header is `法人`. A row that leaves it out keeps the mark of a stored item.
- A row of an assignment kind can carry `actingHead`. The CSV header is `代行`. A row that leaves it out keeps the mark of a stored item.
- A row of a unit type can carry `group`. The CSV header is `グループ`. A row that leaves it out keeps the mark of a stored item.
- The user of an employment is named by `username` or by `userId`. The user must exist.
- The employment belongs to the person of that user. The import creates the person when the user has none.

## CSV

`POST /imports/csv/{list}` takes one file. The list is `ref-items`, `org-units`, `people`, `employments`, or `assignments`.

`GET /imports/csv/org-units?asOf=` gives the org units of a date as such a file, for a change in a spreadsheet. Each row has the column `id`, so a unit with no external ID loads again too. The parent is in the column `parentId`. With `header=ja`, the names of the columns are in Japanese. The file is UTF-8 with a byte order mark, which spreadsheets need to read it as UTF-8. The same file loaded on the same date changes nothing.
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
- A row with more cells than the header fails with `csv.too_many_cells`, unless the extra cells are empty. This is usually a comma that should have been in quotes.
- **A blank cell keeps the stored value.** This differs from JSON, where a missing value clears it. A blank parent keeps the stored parent, also one with no external ID of the source.
- The cell `#CLEAR` removes the stored value.
- True is `true`, `1`, `yes`, `はい`, or `○`. False is `false`, `0`, `no`, `いいえ`, or `×`.
- A name in another language has its own column, for example `name:en` or `名称:en`.
- A column `attributes.<name>` sets an attribute that the realm defined for the resource of the list, for example `attributes.beds`. The cell is read by the type of the definition. Other attributes keep what is stored. A column of an attribute with no definition is rejected.

### Columns

Reference items.

| English | Japanese |
|---|---|
| `kind` | `種類` |
| `code` | `コード` |
| `name` | `名称` |
| `displayOrder` | `表示順` |
| `active` | `有効` |

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
| `parentExternalId` | `親の外部ID` |
| `parentId` | `親のID` |
| `id` | `ID` |
| `displayOrder` | `表示順` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

People.

| English | Japanese |
|---|---|
| `externalId` | `外部ID` |
| `source` | `ソース` |
| `username` | `ユーザー名` |
| `userId` | `ユーザーID` |
| `name` | `名称` |
| `phoneticName` | `よみがな` |

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
| `validFrom` | `開始日` |
| `validTo` | `終了日` |
| `endReason` | `終了理由` |
| `employerExternalId` | `雇用主の外部ID` |
| `personExternalId` | `人の外部ID` |

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
| `head` | `長` |
| `primary` | `主務` |
| `allocationPercent` | `配分割合` |
| `displayOrder` | `表示順` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

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

An export takes every stored row of a realm out, with every period. A restore loads it into a realm that has no data of the extension.
This copies a realm to a test environment, moves it to another Keycloak, or brings it back after its data was lost. It is not the import above, which describes one date.

- `GET /export/{list}` reads one list in pages, ordered by ID. Follow the `Link` header to the end. The lists are `ref-kinds`, `ref-items`, `attribute-definitions`, `hierarchies`, `org-units`, `people`, `employments`, `assignments`, `reporting-lines`, `unit-roles`, `qualifications`, `external-ids`, and `change-orders`.
- Put the lists together under their names in camel case, such as `refKinds`, with `"format": "fuhito.export.v1"`. `POST /restore` takes that document.
- A restore keeps every ID, so products that stored IDs find the same resources. It writes all or nothing.
- It fails with `restore.realm_not_empty` when the realm has any data of the extension. So it cannot load into a realm of the same database that the export came from while that realm still has the rows.
- It checks the export before it writes. A row that names a row that the export does not have fails with 400 and `restore.reference_unknown`. So a restore never links to the rows of another realm.
- A value that is missing or cannot be read fails with 400 and `restore.bad_value`.
- An ID that a row of the database has already, in any realm, fails with 409 and `restore.id_taken`. This happens for a copy into a second realm of the same database while the first realm still has the rows.
- Rows name Keycloak users by username. A restore fails with `restore.username_unknown` when the target realm has no user with that name. A person whose user Keycloak no longer had is exported with no username, and is restored with no link.
- Masked data stays masked, with the time of the mask. Erased data is not in an export.
- The change log, the admin events, the links to Keycloak organizations, and the idempotency keys are not part of an export. The restore records one entry, `FUHITO_RESTORE`.
- The export needs the full read permission, because it has the data of people. The restore needs the permission to write.

Checked by `RealmExportIT.anExportLoadsIntoAnEmptyRealmAndGivesTheSameExportAgain`.

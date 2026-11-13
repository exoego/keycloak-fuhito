# Import and export

This document explains how to load data in bulk and how to read all of it.
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
| Employments | Source and external ID |
| Assignments | Source and external ID |

The source is the name of the system or the sheet that the rows come from.
It keeps the external IDs of two systems apart. The default is `import`.

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
- A new row can have its own `validFrom`. An existing row ignores it.
- A row with `validTo` ends the thing on that date. An employment can also carry `endReason`.
- A row replaces all details of an existing thing. A missing optional value clears it.
- The user of an employment is named by `username` or by `userId`. The user must exist.

## CSV

`POST /imports/csv/{list}` takes one file. The list is `ref-items`, `org-units`, `employments`, or `assignments`.
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
- **A blank cell keeps the stored value.** This differs from JSON, where a missing value clears it.
- The cell `#CLEAR` removes the stored value.
- True is `true`, `1`, `yes`, `はい`, or `○`. False is `false`, `0`, `no`, `いいえ`, or `×`.
- A name in another language has its own column, for example `name:en` or `名称:en`.
- Attributes cannot be set from CSV. They keep what is stored.

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
| `displayOrder` | `表示順` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |

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
| `workPercent` | `勤務割合` |
| `validFrom` | `開始日` |
| `validTo` | `終了日` |
| `endReason` | `終了理由` |

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

## Export

`GET /export` returns all data of the realm in one JSON document.
It has every period of every resource, ended ones included.
Each resource has its `source` and `externalId`.

The export is for reading. It is not in the import format.

# Concepts

This document defines the terms used in this project.
Each term has its Japanese equivalent, so that Japanese readers can map the vocabulary.

## Scope of a realm

One realm holds one legal entity or several, such as a company, a medical corporation, or a group of companies.
All data in this document belongs to one realm.

## Structure

| Term | Japanese | Meaning |
|---|---|---|
| Org Unit | 組織単位、部署 | A node in the organization tree. A hospital, a department, a ward, a team, and a committee are all org units. |
| Unit Type | 組織種別 | The kind of an org unit, for example "hospital" or "department". Each realm defines its own list. |
| Hierarchy | 階層 | A named tree of org units. Each realm has one default hierarchy. More hierarchies put the same units in other trees, such as regions that gather branches of several divisions. |
| Legal Entity | 法人 | An org unit whose unit type has the mark `legalEntity`, such as a company or a medical corporation. A realm can have several. `GET /org-units?legalEntity=true` lists them. |
| Lateral Unit | 横断組織 | An org unit outside the line organization, such as a committee, a project team, or a labor union. The line organization is the vertical structure, and a lateral unit cuts across it. Its unit type has the mark `lateral`. A lateral unit can be a root or sit below any org unit, and lateral units can nest, such as a committee with subcommittees, a labor union with branches, or squads below a tribe. A line unit is never below a lateral unit in the default hierarchy. A lateral unit gives no derived manager. `lateral=true` and `lateral=false` on the reads of the tree pick lateral units or line units. |
| Rollup Unit | 集計組織 | An org unit that gathers org units of other trees in a hierarchy other than the default one, such as a region that gathers branches of several divisions. Its unit type has the mark `rollup`. A rollup unit is not in the default hierarchy, so it has no parent and no child there and is no root there. It can have a parent and children in other hierarchies. See "Rollup units" below. |
| Root | ルート | An org unit with no parent in a hierarchy. A realm can have several roots, for example one for each hospital. `GET /org-units?roots=true` lists them. A rollup unit is no root of the default hierarchy. |

An org unit keeps its identity when its name or its parent changes.
The name and the other details live in dated versions.
The parent lives in dated parent links, one set for each hierarchy.

### Rollup units

A rollup unit exists to gather other units in another hierarchy.
For example, the region "Kanto" gathers the Tokyo branch of one division and the Yokohama branch of another.
Every other org unit is in the default hierarchy.

- A rollup unit is not in the default hierarchy. The reads of the default tree never show it. These are the roots, the children, the subtree below a unit, and the path. Its own subtree and path in the default hierarchy are empty.
- `GET /org-units` still lists it. Its read has `rollup: true`. The list takes `rollup=true` for rollup units only, and `rollup=false` for the others.
- A unit cannot get a rollup type from a date on which it has a parent or a child in the default hierarchy. A rollup unit cannot get a parent or a child there. Both fail with the status 409, `org_unit.rollup_has_parent` or `org_unit.rollup_has_child`, and the message names the rollup unit.
- A unit that leaves a rollup type for another type is a root of the default hierarchy from that date. From then on, it can get a parent and children there.
- A rollup unit can have a head and members. Managers come from the default hierarchy, so its head gets no derived manager from a unit above. Its head is the derived manager of its other members, as in any unit.
- It takes no work location and no cost center from a unit above. It can have its own, and its members take them.
- The projection makes no group for it.
- One unit type cannot carry `rollup` with `lateral` or with `legalEntity`. A lateral unit sits in the default tree, and a legal entity must be there for the check of secondments.
- The mark of a unit type that is in use does not change, as for `lateral`.

## People

A person has a record of its own. It has names in one or more languages, each with a phonetic name. See [Languages](#languages).
A person can be linked to a Keycloak user, and does not need one.
So staff who never log in, and people who left long ago, can be stored too.
The free attributes of a person stay in the extension for the same reason. See [Values of people and user attributes of Keycloak](#values-of-people-and-user-attributes-of-keycloak).

An employment and a qualification belong to a person. The manager of a reporting line is a person.
A write can name the person by the ID of a Keycloak user. The person of that user is then used, and created when the user has none.
Reads, the mask, and the erase work by person. Each has a shortcut by the ID of a Keycloak user.
`GET /people?q=` finds people by a part of the name or the phonetic name of any language, in any case. A person with a Keycloak user is also found by the first name, the last name, the username, or the email of the user. `GET /people?staffNumber=` finds the people with an employment that has the number. A masked person has no name to find.

| Term | Japanese | Meaning |
|---|---|---|
| Person | 人 | Someone the realm knows. The link to a Keycloak user is optional. One user is linked to at most one person. |
| Employment | 雇用、在籍 | The relationship between a person and an employer, for a period. The employer is a legal entity of the realm, or an external employer. It never changes for one employment. It also covers people who are not direct employees, such as agency staff (派遣社員) and contractors. Their employer is the agency or the contractor, an external employer. Leaving ends the employment. |
| External Employer | 外部の雇用主 | An employer outside the realm, such as a staffing agency or a contractor. It is a reference item of the kind `EXTERNAL_EMPLOYER`, not an org unit. |
| Employment Terms | 雇用条件 | The conditions of an employment that change over time. They are the employment type, the grade, the status with its leave reason, the work ratio, and the contract end date. |
| Contract End Date | 契約期間満了日 | The last day of the fixed-term contract in force. An indefinite contract has none. |
| Employment Type | 雇用形態 | The category of the contract, for example full-time, part-time, or temporary. |
| Grade | 等級 | A rank level used for pay or seniority. |
| Status | 状態 | Whether the person is active, on leave, or suspended. |
| Leave Reason | 休職理由 | Why a person is on leave, for example parental leave or sick leave. Only terms with the status "on leave" have one. |
| End Reason | 終了理由 | Why an employment ended, for example resignation or retirement at the age limit. |
| Assignment | 所属、配属 | The placement of an employment in an org unit, for a period. |
| Primary Assignment | 主務、本務 | The one assignment marked as the main one. It is optional. |
| Concurrent Assignment | 兼務 | Any further assignment held at the same time. There is no limit on the number. |
| Job Title | 役職 | A managerial or functional title held in an assignment, for example "director" or "head nurse". |
| Occupation | 職種 | The profession or kind of work in an assignment, for example "physician", "nurse", or "clerk". |
| Assignment Kind | 所属区分 | How the assignment is held, for example regular, acting, or support. A kind with the mark `actingHead` makes its heads stand in for an absent head. |
| Work Location | 勤務地 | The site where a person works, for example an office, a plant, a store, a hospital site, or home. It is a reference item of the kind `WORK_LOCATION`. An address, a country, or a time zone can be attributes of the item. |
| Cost Center | コストセンター | The accounting unit that bears the cost of a person. It is a reference item of the kind `COST_CENTER`. The items are a flat list, not a tree. |
| Establishment | 事業場 | The place that labor law counts by, for the worker register, the overtime agreement, and the work rules. It is an attribute of the work location item, not a field. A realm defines it for reference items with `POST /attribute-definitions`. |
| Head | 長 | A flag on an assignment. It marks the person who leads the org unit. |
| Acting Head | 代行、事務取扱 | A head whose assignment kind has the mark `actingHead`. It leads the unit while its assignment holds, in place of the other heads. |
| Reporting Line | レポートライン | A link from an assignment to a manager. It has a kind, solid or dotted, and a period. |
| Unit Role | 組織への役割 | A role that an employment holds over an org unit without being a member, such as the HR contact of a division or the approver of expenses of a department. The role is a reference item of the kind `UNIT_ROLE`. With `includesDescendants`, it covers the units below too. `GET /org-units/{id}/role-holders?nearest=true` keeps, for each role, only the holders of the nearest unit, the way an approval goes up until it finds an approver. `GET /unit-roles` lists the roles of a date across the realm, for example every safety officer. Its `orgUnitId` keeps the roles over that unit, without the ones that cover it from a unit above. It is data for products. The extension decides no access with it. |
| Qualification | 資格、免許 | A license or certificate that a person holds, with a number and a period. |

A fixed-term contract always has an end date, and an indefinite one has none. So `contractEndDate` of the terms also tells which of the two a contract is.
A renewal is new terms from the first day of the next contract, with a later date. The versions where the date changes give the history of the contracts.
The end of the employment means that the person leaves. A contract end does not end the employment, so a renewal that is written late does not drop the person from the units.
`GET /employments?contractEndFrom=&contractEndTo=` lists the employments whose contract ends in that range, by the terms of `asOf` or of today.

### Work location and cost center

The details of an org unit and of an assignment have `workLocation` and `costCenter`.
The values of an org unit are the defaults for its members, and they are dated with the details of the unit.
So a unit that moves to a new site is one change of its details. A person who works elsewhere, such as from home, sets the value on the assignment.

- The value that applies to an assignment is its own value. Without one, it is the value of its org unit on the date. Without that, it is the value of the nearest unit above in the default hierarchy on the date.
- The value that applies to an org unit is its own value, else the value of the nearest unit above.
- The work location and the cost center each follow this rule on their own. A unit can set only a cost center, and take the work location from above.
- The reads of org units and of assignments return the value of the record in `workLocation` and `costCenter`, and the value that applies in `effectiveWorkLocation` and `effectiveCostCenter`.
- A read of an org unit is on a date. A read of an assignment gives each period the values on its date nearest to today. The history of an org unit has the values of the records only.
- The reads of members and of the assignments of a person return the four fields too, on the date of the read. So does the list of assignments across the realm. The token mapper adds the values that apply.
- `GET /org-units/{id}/members` takes `workLocation` and `costCenter` with a code. It keeps the members whose value that applies on the date is that one. The database applies the filter, so the pages and the count stay right. An unknown code fails with `ref_item.unknown`.
- The values that apply always come from the default hierarchy. A read of members below a unit in another hierarchy filters by them too.
- A move to another org unit does not keep the values of the assignment, because they belong to the old unit. The move can give new ones. A transfer to another employer keeps them.

Job title, occupation, grade, and employment type are four separate things.
One person can be a nurse (occupation), a head nurse (job title), grade 4 (grade), and full-time (employment type).

This project does not use the word "Position" for a job title.
Other systems use "Position" for a seat that exists even when nobody holds it.

## Time

| Term | Japanese | Meaning |
|---|---|---|
| Valid Period | 有効期間 | The dates when a fact is true. It is a half-open range. The start date is included, and the end date is not. No end date means "until further notice". |
| As-of Date | 基準日 | The date for which a read returns the data. The default is today. |
| Effective Date | 発効日 | The date from which a change is true. It can be in the past or in the future. |
| Change Order | 発令、変更指示 | An approved instruction that causes changes on an effective date. It records who approved it. A personnel order and a reorganization are change orders. |
| Change Log | 変更ログ | An append-only record of who changed what and when. |

The valid period says when something was true in the real world.
The change log says when the system learned about it.

### Names of date and time fields

A field with a date or a time is one of two kinds, and its name tells which.

| Name | Kind | Who sets it | Can be in the future |
|---|---|---|---|
| Ends in `At`, such as `recordedAt`, `createdAt`, and `maskedAt` | A time that the system recorded. It is a fact. | The system, when the thing happens | No |
| Ends in `Date`, such as `effectiveDate` and `orderDate`, and `validFrom` and `validTo` | A date of the business. It can be a plan. | The request | Yes |

A date has the form `YYYY-MM-DD`, from `0001-01-01` to `9999-12-31`. Every date of a write, of an import, of a restore, and of a query such as `asOf` must be in that range. Java reads other dates too, but PostgreSQL cannot hold some of them and gives year 0 back as year 1. These are the years that the form writes with no sign, without year 0.

A date of the business does not say whether the thing happened. An end of an employment next month is a plan today, and the field is the same after that day.
When a reader must know that something happened, the field is an `At` field that an operation sets. No request gives it a value.

## Reference data

Unit types, job titles, occupations, employment types, grades, end reasons, assignment kinds, qualification kinds, leave reasons, unit roles, work locations, cost centers, and external employers are reference data.
Each realm defines its own items.
An item has a code, a name, and optional names in other languages.
The names have valid periods, so a rename that keeps the code shows the old name in a read of an old date. The code and the other values have no dates. See [history.md](history.md).

These kinds are fixed, because fields of the resources point to them. A realm can add kinds of its own with `POST /ref-kinds`, for example a working time system or a collective agreement. `PUT /ref-kinds/{code}` changes their names. The code never changes, and a `PUT` with another code fails with 400 and `invalid.value_fixed`.

- The items of such a kind are reference items like the others. `/ref-items?kind=<code>` reads and writes them, and the import takes them.
- A free attribute of the type `REF_ITEM` can point to such a kind, so that its value is the code of one of its items.
- A kind of the realm can be removed when it has no items.

## IDs in other systems

Every resource with an ID of its own can have an ID in each system that knows it, such as an HR system, a payroll system, and a time keeping system.
These are org units, people, employments, assignments, qualifications, reporting lines, roles over org units, change orders, reference items, kinds of reference data that a realm added, hierarchies, and attribute definitions.
Every ID of every system is one row of one table, also the ID of the system that made the resource.
An org unit, a person, an employment, an assignment, and a qualification keep `source`, the name of the system that made them. The other resources keep no system.

- A write with `source` and `externalId` stores the ID of that system for the resource. It replaces the ID that the system had. A write without them leaves the IDs as they are.
- A create also sets `source` of the resource. It never changes, also when the ID of that system is removed. A `PUT` with no `externalId` can give the `source` that a read has. Another `source` then fails with 400 and `invalid.value_fixed`.
- The other resources keep no system, so `source` names only the system of `externalId`. The `PUT` of a reference item, a kind of reference data, a hierarchy, an attribute definition, or a change order stores the ID as above. A `PUT` with `source` and no `externalId` fails with 400 and `invalid.value_fixed`. A reporting line and a role over an org unit take `source` and `externalId` only in a create. `PUT /external-ids` changes the IDs of every resource.
- A read gives `externalIds`, so a write can give it too. A `PUT` with another list fails with 400 and `invalid.value_fixed`, as with the other fields that only a response fills.
- A read of a resource gives `externalIds`, the list of every ID, and `source` when the resource keeps it. The ID of the system of `source` comes first, then the others by system. A resource with no ID has no `externalIds`. A read of many resources reads the IDs of all of them at once.
- An ID is part of its resource. A change of an ID counts up the version of the resource, once in a write.
- `PUT /external-ids` stores the ID of one system for one resource, and `DELETE /external-ids` removes it.
- A resource has at most one ID for each system.
- One ID of one system names one resource of a kind.
- `GET /external-ids/lookup?resourceType=&source=&externalId=` finds the resource.
- `GET /external-ids?resourceType=&resourceId=` lists every ID of one resource, the ID of the system that made it first. That one has `own`.
- The import matches a row by the ID of any system. A file of the payroll system finds a person that the HR system made, when the person has an ID of the payroll system.
- A resource that goes away takes its IDs with it. A transfer to another employer is the one exception. It removes each assignment, reporting line, and role over an org unit that starts on its date or later, and gives their IDs to their copies. A mask and an erase remove the IDs of the person and of the records of the person. These records are the employments, the assignments, the qualifications, the roles over org units, and the reporting lines where the person reports or is the manager.
- The IDs of an org unit, a reference item, a kind of reference data, a hierarchy, and an attribute definition need the permission to read the org structure. The others need the full read.

## Free attributes

Org units, people, employment terms, assignments, qualifications, and reference items have `attributes`, a JSON object for values that the realm needs.
A realm defines each name with `POST /attribute-definitions` before a write gives it, so that products use one name for one thing.

- The attributes of one write are at most 64 KB as JSON, counted in bytes of UTF-8. A larger write fails with `attribute.too_large`.
- A definition is for one kind of resource and one name. It has a type, `STRING`, `NUMBER`, `BOOLEAN`, `DATE`, or `REF_ITEM` with a kind of reference data. A `DATE` value is a text in the form `YYYY-MM-DD`, in the range of every other date.
- A definition with `multiple` holds a list. The value is a JSON array, and each element has the type. A `REF_ITEM` list checks the code of each element.
- A text that is empty or only spaces fails with `invalid.blank`, also an element of a list, as a CSV cell cannot hold such a text. An empty array fails with `invalid.blank` too. The error names the field, for example `attributes.shifts`. Leave the name out, or give null, for no value.
- A text with NUL (U+0000) or with half of a surrogate pair fails with `invalid.character`, as in every other field, because the database cannot store it as sent. This holds for the name of a definition and for a key in `attributes` too. The error names the field and the code point.
- Every write of the resource is checked against the definitions. A value of the wrong type fails with `attribute.bad_value`. A missing required one fails with `attribute.required`.
- A name with no definition fails with `attribute.unknown`.
- An object has no type. Split it into several names, such as `addressZip` and `addressCity`.
- A new, changed, or removed definition does not rewrite stored values. A row is checked at its next write. A row that keeps a value of a removed definition fails until the write drops the value.
- A reader of the org structure may read the definitions.
- The values are JSON text in the database. No read searches inside them. A value that a read must filter by needs a field of its own.

### Values of people and user attributes of Keycloak

The values of a person stay in the extension, so that people without a Keycloak user have them too.
User attributes of Keycloak are for what login and tokens need.

| | User attributes of Keycloak | Attributes of a person |
|---|---|---|
| Names | One set for the realm, shared with other extensions, LDAP, and identity providers | The extension only |
| Declared in | The user profile. Keycloak ignores the others by default | `/attribute-definitions` |
| Values | Lists of text | JSON, with types and lists |
| People without a user | Have none | Have them |

## Languages

Names can have text in several languages, for example Japanese and English.
An org unit and a person also have a phonetic name (よみがな) for sorting.
It is not a translation.

An org unit keeps its names for each language, with a name, a short name, and a phonetic name.
A language is a BCP 47 tag, such as `ja`, `en`, or `zh-Hant`.
One language of each version is the primary one.

```json
{
  "name": "Sales Division",
  "shortName": "Sales",
  "primaryLanguage": "ja",
  "names": {
    "ja": {"name": "営業部", "shortName": "営業", "phoneticName": "えいぎょうぶ"},
    "en": {"name": "Sales Division", "shortName": "Sales"}
  }
}
```

- A write sends `primaryLanguage` and `names`. `primaryLanguage` must be a key of `names`.
- A write stores each tag in its standard form, such as `ja` for `JA` and `en-US` for `en-us`. `primaryLanguage` matches a key in that form, so `JA` matches the key `ja`.
- Two keys of one tag in standard form, such as `ja` and `JA`, fail with 400 and `invalid.duplicate_language`. The error names the second key in `field`, such as `names.JA`, and the tag in `value`. Nothing is written. This holds for a REST write, an import, and a restore.
- A read gives `name`, `shortName`, and `phoneticName` in the language of the reader, and every language in `names`.
- The names of a language come from that tag, then from the language with no region, such as `en` for `en-US`, then from the primary language.
- A read of the API takes the first language of `Accept-Language`. With no `Accept-Language`, it gives the primary names.
- Other reads that name a unit, such as the path of a member or a list of role holders, give the name in the language of the request too.
- A token gives the names in the language that the user picked, else in the default language of the realm. A realm with no languages turned on gives English.
- The Keycloak group of a unit has the primary name. So do the check of the names of siblings and the match of the attribute sync.
- Lists sort siblings by the primary phonetic name and the primary name, whatever the language of the reader.
- A search matches the name, the short name, or the phonetic name of any language.
- A change of the default language of the realm changes no name. A unit can have its own primary language.

A person keeps its names the same way, with a name and a phonetic name for each language.
A person has no short name.

```json
{
  "name": "Taro Yamada",
  "nameSource": "RECORD",
  "primaryLanguage": "ja",
  "names": {
    "ja": {"name": "山田 太郎", "phoneticName": "やまだ たろう"},
    "en": {"name": "Taro Yamada"}
  }
}
```

- A write and a read of a person work as for an org unit, with `name` and `phoneticName` in the language of the reader.
- A person with a linked Keycloak user can have no names. A response then has the name of the user in `name`, and an empty `names`. A person with no linked user needs names.
- A search matches the name or the phonetic name of any language. It also matches the user of a person, as [People](#people) says.
- A mask and an erase remove the names of every language.

A reference item keeps its names the same way, with only a name for each language.
Each period of the names of an item has its own languages and its own primary language. See [Reference items keep their old names](history.md#reference-items-keep-their-old-names).

```json
{
  "code": "manager",
  "name": "Manager",
  "primaryLanguage": "ja",
  "names": {
    "ja": {"name": "部長"},
    "en": {"name": "Manager"}
  }
}
```

- A write and a read of an item work as for an org unit, with `name` in the language of the reader.
- Other reads that name an item, such as the role names of `GET /unit-roles`, give the name in the language of the request too.
- A token has the codes of items, not their names.
- The attribute sync matches a job title by its code or by its primary name.

A kind of reference data that a realm added keeps its names the same way, with only a name for each language.

```json
{
  "code": "work-location",
  "name": "Work location",
  "primaryLanguage": "ja",
  "names": {
    "ja": {"name": "勤務地"},
    "en": {"name": "Work location"}
  }
}
```

- A write and a read of a kind work as for an org unit, with `name` in the language of the reader.
- A fixed kind such as `UNIT_TYPE` has no names.

## Examples

### A small clinic

- One root org unit for the clinic.
- Each staff member has one employment and one assignment to the root.
- Job titles and grades are not used.

### A corporation with several hospitals

- One root org unit for each hospital.
- Each hospital has departments and wards below it. Its committees are lateral units below the hospital.
- A physician has a primary assignment in one hospital and a concurrent assignment in another.
- A part-time nurse has employment terms with a part-time employment type and a work ratio.
- A nurse on parental leave has the status "on leave" and the leave reason "parental leave". The assignment stays.
- A nurse who accepted an offer has an employment that starts on the first day. Reads before that date do not show the nurse as a member.
- A reorganization on April 1 is one change order that renames and moves many units on that date.

### A general company

- One root for the company, with divisions, departments, and sections below it.
- Each employee has a grade.
- A second hierarchy puts the same org units in another tree, such as regions that gather branches of several divisions. `POST /hierarchies` creates it, and `PUT /org-units/{id}/parent?hierarchy=` gives a unit a parent in it.
- A region is a rollup unit. It is in the second hierarchy only, and the default tree does not show it.
- A person is in an org unit, not in a hierarchy. So the members of a unit show in every tree that has the unit.
- A project team is a lateral unit. Its members hold concurrent assignments, and keep their managers in the line organization.
- A dotted reporting line links a member to a project manager.

## Employers

Every employment names its employer. The employer is an org unit that is a legal entity on the first date of the employment, or an external employer.

- An org unit is a legal entity when its unit type has the mark `legalEntity`. Mark the types such as "company" and "medical corporation" once.
- A realm needs at least one legal entity before its first employment.
- A request can leave the employer out when the realm has exactly one legal entity on that date. With several, the request must name it. Nothing is guessed from the org unit where the person works.
- The employer of an employment never changes. A move to another employer is the end of one employment and the start of another.
- `POST /employments/{id}/transfer` does that move in one write. The new employment gets the terms, the assignments, the reporting lines, and the roles over org units of the old one from the date on, and names the old one in `continuesEmploymentId`. So the years of service can be counted across the two. The new employment keeps the end of the old one. The new employer must exist until that end, or the transfer fails with `employment.employer_ends` and writes nothing. The new terms keep their contract end. A contract in the terms of the date that ends before the date fails with `employment.contract_end_before_terms`.
- An assignment, a reporting line, or a role over an org unit that starts on the date of the transfer or later goes away from the old employment. Its copy takes its IDs of other systems, so a system that knows it by such an ID still finds it. One that holds before the date only ends and keeps its IDs. Its copy gets none.
- `POST /employments/transfers` does it for every person of one employer, for a merger of two legal entities. It can end the old employer in the same write. Both employers are legal entities on the date. A request that moves nobody gets the same checks, so a bad value fails and writes nothing. With good values and `endEmployer`, it still ends the old employer.
- A legal entity stays one while employments name it. Its unit type keeps the mark, the unit does not get a type without the mark from a date on which an employment starts, and the unit does not end before the employments do.
- A staff number is unique for one employer. Two employers can give the same number to two people.
- A person who is employed by one legal entity and works in an org unit of another is seconded. The employment names the first, and the assignment names the org unit.
- Each org unit belongs to one legal entity on a date, or to none. A legal entity belongs to itself. Any other unit belongs to the nearest legal entity above it in the default hierarchy. The unit types of the date decide which units are legal entities.
- A legal entity can sit below another one, such as a company below its holding company. A branch of the company belongs to the company, not to the holding company.
- A unit with no legal entity above it in the default hierarchy belongs to none. Such units are a rollup unit, a lateral root such as a labor union, and a unit above every legal entity.
- The reads of members and of the assignments of a person have `employerId` and `seconded`. An assignment is a secondment when the employer of the employment is a legal entity and the org unit belongs to another legal entity on the date. A unit that belongs to no legal entity gives no secondment, and neither does an external employer.
- `GET /org-units/{id}/members` takes `seconded=true` for only the seconded people, and `seconded=false` for everyone else. The database applies the filter, so the pages stay right.

For example, with a holding company H above company A, and company B beside them:

| Employer | Org unit | Seconded |
|---|---|---|
| A | A rollup unit "Kanto" | No |
| A | A labor union at the root | No |
| A | A branch of B | Yes |
| H | A branch of A | Yes |
| A | A branch of A | No |
| A staffing agency | A branch of B | No |

### External employers

A dispatched worker (派遣社員) or a contractor is employed by an agency outside the realm. The agency is an external employer. It is a reference item of the kind `EXTERNAL_EMPLOYER`, with a code, names by language, attributes, IDs of other systems, and `active`, like other reference items. It is not an org unit, so it is not in any tree.

- An employment names it by its code in `externalEmployer`, in place of `employerId`. A request with both fails with `employment.employer_twice`. A request with neither works as before.
- A new employment needs an active item. An unknown code fails with `employment.external_employer_unknown`, and an inactive item with `employment.external_employer_inactive`. A stored employment keeps an item that is no longer active.
- An external employer is no legal entity. A realm with exactly one legal entity still lets a request leave the employer out.
- The external employer of an employment never changes. `POST /employments/{id}/transfer` rejects an employment with an external employer with `employment.transfer_external`. `POST /employments/transfers` moves the people of a legal entity only. A move from an agency to direct employment ends one employment and starts another.
- A staff number is unique for one external employer, as for one legal entity.
- An employment with an external employer is never a secondment. The reads of an employment, of members, and of the assignments of a person have `externalEmployer` and no `employerId`.
- `GET /employments?externalEmployer=` lists the employments of one external employer. It can name an item that is no longer active.
- An item that an employment names is in use, so it cannot be deleted. Deactivate it instead.
- The sync from user attributes names the employer by the code of an org unit, so its new employments have a legal entity.

## Out of scope

- Access control. The caller decides who may do what.
- Seats and headcount control. A vacant head post is a unit with no head. A number of posts can go into the attributes of the org unit.
- Shift plans and daily duty rosters.
- People who work for legal entities in different realms. Legal entities in one realm are in scope.

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
| Hierarchy | 階層 | A named tree of org units. Each realm has one default hierarchy. More hierarchies can describe other views of the same units, such as a matrix organization. |
| Legal Entity | 法人 | An org unit whose unit type has the mark `legalEntity`, such as a company or a medical corporation. A realm can have several. `GET /org-units?legalEntity=true` lists them. |
| Group | グループ | An org unit outside the line organization, such as a committee, a project team, or a labor union. Its unit type has the mark `group`. A group can be a root or sit below any org unit. A line unit is never below a group in the default hierarchy. A group gives no derived manager. `group=true` and `group=false` on the reads of the tree pick groups or line units. |
| Root | ルート | An org unit with no parent in a hierarchy. A realm can have several roots, for example one for each hospital. |

An org unit keeps its identity when its name or its parent changes.
The name and the other details live in dated versions.
The parent lives in dated parent links, one set for each hierarchy.

## People

A person has a record of its own. It has a name, names in other languages, and a phonetic name.
A person can be linked to a Keycloak user, and does not need one.
So staff who never log in, and people who left long ago, can be stored too.

An employment and a qualification belong to a person. The manager of a reporting line is a person.
A write can name the person by the ID of a Keycloak user. The person of that user is then used, and created when the user has none.
Reads, the mask, and the erase work by person. Each has a shortcut by the ID of a Keycloak user.
`GET /people?q=` finds people by a part of a name, the phonetic name, or a name in another language, in any case. A person with a Keycloak user is also found by the first name, the last name, the username, or the email of the user. `GET /people?staffNumber=` finds the people with an employment that has the number. A masked person has no name to find.

| Term | Japanese | Meaning |
|---|---|---|
| Person | 人 | Someone the realm knows. The link to a Keycloak user is optional. One user is linked to at most one person. |
| Employment | 雇用、在籍 | The relationship between a person and a legal entity, for a period. The legal entity is the employer. It never changes for one employment. It also covers people who are not direct employees, such as agency staff and contractors. Leaving ends the employment. |
| Employment Terms | 雇用条件 | The conditions of an employment that change over time. They are the employment type, the grade, the status with its leave reason, and the work ratio. |
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
| Head | 長 | A flag on an assignment. It marks the person who leads the org unit. |
| Acting Head | 代行、事務取扱 | A head whose assignment kind has the mark `actingHead`. It leads the unit only while no other head is present. |
| Reporting Line | レポートライン | A link from an assignment to a manager. It has a kind, solid or dotted, and a period. |
| Unit Role | 組織への役割 | A role that an employment holds over an org unit without being a member, such as the HR contact of a division or the approver of expenses of a department. The role is a reference item of the kind `UNIT_ROLE`. With `includesDescendants`, it covers the units below too. It is data for products. The extension decides no access with it. |
| Qualification | 資格、免許 | A license or certificate that a person holds, with a number and a period. |

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

A date of the business does not say whether the thing happened. An end of an employment next month is a plan today, and the field is the same after that day.
When a reader must know that something happened, the field is an `At` field that an operation sets. No request gives it a value.

## Reference data

Unit types, job titles, occupations, employment types, grades, end reasons, assignment kinds, qualification kinds, leave reasons, and unit roles are reference data.
Each realm defines its own items.
An item has a code, a name, and optional names in other languages.

These kinds are fixed, because fields of the resources point to them. A realm can add kinds of its own with `POST /ref-kinds`, for example a work location or a cost center.

- The items of such a kind are reference items like the others. `/ref-items?kind=<code>` reads and writes them, and the import takes them.
- A free attribute of the type `REF_ITEM` can point to such a kind, so that its value is the code of one of its items.
- A work location is such an attribute of assignments for now. A field of its own can come when a product needs to filter by it.
- A kind of the realm can be removed when it has no items.

## IDs in other systems

An org unit, a person, an employment, an assignment, and a qualification have one pair of `source` and `externalId`. It is the ID in the system that made the resource, and the import matches rows by it.
Other systems can add their own IDs with `PUT /external-ids`.

- A resource has at most one ID for each system, counting its own pair.
- One ID of one system names one resource of a kind.
- `GET /external-ids/lookup?resourceType=&source=&externalId=` finds the resource. It looks at the own pairs and at the added IDs.
- `GET /external-ids?resourceType=&resourceId=` lists every ID of one resource, the own pair first.
- The IDs of an org unit need the permission to read the org structure. The others need the full read.

## Free attributes

Org units, people, employment terms, assignments, qualifications, and reference items have `attributes`, a JSON object for values that the realm needs.
A realm can define them with `POST /attribute-definitions`, so that products use one name for one thing.

- The attributes of one write are at most 64 KB as JSON, counted in bytes of UTF-8. A larger write fails with `attribute.too_large`. This holds with or without definitions.

- A definition is for one kind of resource and one name. It has a type, `STRING`, `NUMBER`, `BOOLEAN`, `DATE`, or `REF_ITEM` with a kind of reference data.
- Every write of the resource is checked against the definitions. A value of the wrong type fails with `attribute.bad_value`. A missing required one fails with `attribute.required`.
- An attribute with no definition is allowed. The realm attribute `fuhito.attributes.unknown` with the value `reject` makes such a write fail with `attribute.unknown`.
- A new, changed, or removed definition does not rewrite stored values. A row is checked at its next write.
- A reader of the org structure may read the definitions.

## Languages

Names can have text in several languages, for example Japanese and English.
An org unit also has a phonetic name (よみがな) for sorting.
It is not a translation.

## Examples

### A small clinic

- One root org unit for the clinic.
- Each staff member has one employment and one assignment to the root.
- Job titles and grades are not used.

### A corporation with several hospitals

- One root org unit for each hospital.
- Each hospital has departments and wards below it. Its committees are groups below the hospital.
- A physician has a primary assignment in one hospital and a concurrent assignment in another.
- A part-time nurse has employment terms with a part-time employment type and a work ratio.
- A nurse on parental leave has the status "on leave" and the leave reason "parental leave". The assignment stays.
- A nurse who accepted an offer has an employment that starts on the first day. Reads before that date do not show the nurse as a member.
- A reorganization on April 1 is one change order that renames and moves many units on that date.

### A general company

- One root for the company, with divisions, departments, and sections below it.
- Each employee has a grade.
- A matrix organization uses a second hierarchy for the other view. `POST /hierarchies` creates it, and `PUT /org-units/{id}/parent?hierarchy=` gives a unit a parent in it.
- A project team is a group. Its members hold concurrent assignments, and keep their managers in the line organization.
- A dotted reporting line links a member to a project manager.

## Employers

Every employment names its employer. The employer is an org unit that is a legal entity on the first date of the employment.

- An org unit is a legal entity when its unit type has the mark `legalEntity`. Mark the types such as "company" and "medical corporation" once.
- A realm needs at least one legal entity before its first employment.
- A request can leave the employer out when the realm has exactly one legal entity on that date. With several, the request must name it. Nothing is guessed from the org unit where the person works.
- The employer of an employment never changes. A move to another employer is the end of one employment and the start of another.
- `POST /employments/{id}/transfer` does that move in one write. The new employment gets the terms, the assignments, the reporting lines, and the roles over org units of the old one from the date on, and names the old one in `continuesEmploymentId`. So the years of service can be counted across the two.
- `POST /employments/transfers` does it for every person of one employer, for a merger of two legal entities. It can end the old employer in the same write.
- A legal entity stays one while employments name it. Its unit type keeps the mark, the unit does not get a type without the mark from a date on which an employment starts, and the unit does not end before the employments do.
- A staff number is unique for one employer. Two legal entities can give the same number to two people.
- A person who is employed by one legal entity and works in an org unit of another is seconded. The employment names the first, and the assignment names the org unit.
- The reads of members and of the assignments of a person have `employerId` and `seconded`. An assignment is a secondment when its org unit is not the employer and not below it in the default hierarchy.
- `GET /org-units/{id}/members` takes `seconded=true` for only the seconded people, and `seconded=false` for everyone else. The database applies the filter, so the pages stay right.

## Out of scope

- Access control. The caller decides who may do what.
- Seats and headcount control. A vacant head post is a unit with no head. A number of posts can go into the attributes of the org unit.
- Shift plans and daily duty rosters.
- People who work for legal entities in different realms. Legal entities in one realm are in scope.

# Concepts

This document defines the terms used in this project.
Each term has its Japanese equivalent, so that Japanese readers can map the vocabulary.

## Scope of a realm

One realm is one legal entity, such as a company or a medical corporation.
All data in this document belongs to one realm.

## Structure

| Term | Japanese | Meaning |
|---|---|---|
| Org Unit | 組織単位、部署 | A node in the organization tree. A hospital, a department, a ward, a team, and a committee are all org units. |
| Unit Type | 組織種別 | The kind of an org unit, for example "hospital" or "department". Each realm defines its own list. |
| Hierarchy | 階層 | A named tree of org units. Each realm has one default hierarchy. More hierarchies can describe other views of the same units, such as a matrix organization. |
| Root | ルート | An org unit with no parent in a hierarchy. A realm can have several roots, for example one for each hospital. |

An org unit keeps its identity when its name or its parent changes.
The name and the other details live in dated versions.
The parent lives in dated parent links, one set for each hierarchy.

## People

A person has a record of its own. It has a name, names in other languages, and a phonetic name.
A person can be linked to a Keycloak user, and does not need one.
So staff who never log in, and people who left long ago, can be stored too.

An employment belongs to a person.
A write can name the person by the ID of a Keycloak user. The person of that user is then used, and created when the user has none.

Qualifications and reporting lines still name a Keycloak user today.
They will name a person. See [issue 140](https://github.com/exoego/keycloak-fuhito/issues/140).

| Term | Japanese | Meaning |
|---|---|---|
| Person | 人 | Someone the realm knows. The link to a Keycloak user is optional. One user is linked to at most one person. |
| Employment | 雇用、在籍 | The relationship between a person and the legal entity, for a period. It also covers people who are not direct employees, such as agency staff and contractors. Leaving ends the employment. |
| Employment Terms | 雇用条件 | The conditions of an employment that change over time. They are the employment type, the grade, the status, and the work ratio. |
| Employment Type | 雇用形態 | The category of the contract, for example full-time, part-time, or temporary. |
| Grade | 等級 | A rank level used for pay or seniority. |
| Status | 状態 | Whether the person is active, on leave, or suspended. |
| End Reason | 終了理由 | Why an employment ended, for example resignation or retirement at the age limit. |
| Assignment | 所属、配属 | The placement of an employment in an org unit, for a period. |
| Primary Assignment | 主務、本務 | The one assignment marked as the main one. It is optional. |
| Concurrent Assignment | 兼務 | Any further assignment held at the same time. There is no limit on the number. |
| Job Title | 役職 | A managerial or functional title held in an assignment, for example "director" or "head nurse". |
| Occupation | 職種 | The profession or kind of work in an assignment, for example "physician", "nurse", or "clerk". |
| Assignment Kind | 所属区分 | How the assignment is held, for example regular, acting, or support. |
| Head | 長 | A flag on an assignment. It marks the person who leads the org unit. |
| Reporting Line | レポートライン | A link from an assignment to a manager. It has a kind, solid or dotted, and a period. |
| Qualification | 資格、免許 | A license or certificate that a user holds, with a number and a period. |

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

## Reference data

Unit types, job titles, occupations, employment types, grades, end reasons, assignment kinds, and qualification kinds are reference data.
Each realm defines its own items.
An item has a code, a name, and optional names in other languages.

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
- Each hospital has departments, wards, and committees below it.
- A physician has a primary assignment in one hospital and a concurrent assignment in another.
- A part-time nurse has employment terms with a part-time employment type and a work ratio.
- A nurse on parental leave has the status "on leave". The assignment stays.
- A reorganization on April 1 is one change order that renames and moves many units on that date.

### A general company

- One root for the company, with divisions, departments, and sections below it.
- Each employee has a grade.
- A matrix organization uses a second hierarchy for the other view.
- A project team is an org unit with its own unit type. Its members hold concurrent assignments.
- A dotted reporting line links a member to a project manager.

## Out of scope

- Access control. The caller decides who may do what.
- Seats and headcount control.
- Shift plans and daily duty rosters.
- People who work for several legal entities across realms.

# History and dated changes

This document explains how dated changes behave.
Each rule names the integration test that checks it. The tests are in `src/integrationTest`.

## Two kinds of time

| Question | Where the answer is |
|---|---|
| When is a fact true? | The valid period of a row, `validFrom` and `validTo` |
| When did the system learn it, from whom, and under which approval? | The change log |

A valid period includes its `validFrom` date and does not include its `validTo` date.
A missing `validTo` means that the fact has no end.

For example, a rename that takes effect on 2027-04-01 can be stored on 2027-02-10.
The valid period of the new name starts on 2027-04-01.
The change log entry has the time of 2027-02-10.

## Resources with history

| Resource | What has a valid period |
|---|---|
| Org unit | Its details, and its parent in a hierarchy |
| Employment | The employment itself, and its terms |
| Assignment | Its details |

Each resource keeps its ID through all changes.

## Reading

- Every read of a tree or a list takes `asOf`. It returns what is true on that date.
- The default of `asOf` is today in the time zone of the server. Send `asOf` when the client is in another time zone.
- `GET /org-units/{id}/history` returns every period of an org unit.
- `GET /employments/{id}` and `GET /assignments/{id}` return every period of their resource.
- `GET /users/{userId}/history` returns every employment and assignment of a user.

Checked by `OrgUnitTreeIT.listsTheWholeTreeOnTwoDates`, `OrgUnitTreeIT.readsTheHistoryOfAUnit`, `MembershipIT.listsTheMembersOfAUnitOnTwoDates`, and `MembershipIT.readsTheHistoryOfAUserWhoLeftAndCameBack`.

## A change on a date

A change says "from this date, the details are these".
It replaces all details. A missing optional value clears it.

### A change on a future date

The change is stored now and becomes true on its date.
A read before that date still returns the old details.

Checked by `OrgUnitsIT.renamesAUnitOnAnEffectiveDate`.

### A change inside a past period

The change splits the stored period at its date.
The new details last until the next stored change. Later changes stay as they are.

For example, a unit has the name A from 2026-04-01 and the name C from 2027-04-01.
A change to the name B on 2026-10-01 gives A, then B from 2026-10-01, then C from 2027-04-01.

Checked by `OrgUnitsIT.changesInsideAPastPeriodAndCorrectsAFirstDate` and `EmploymentsIT.changesTermsForALeaveAReturnAndANewEmploymentType`.

### A change on the first date of a stored period

The change replaces the details of that period. No new period is made.
This is how to correct a mistake.

Checked by `OrgUnitsIT.changesInsideAPastPeriodAndCorrectsAFirstDate` and `OrgUnitsIT.movesAUnitAndMakesItARoot`.

## An end

An end says "from this date, this no longer holds".
The rules differ by resource, because the right answer to "what about the stored future" differs.

| Resource | Stored changes on or after the end date | Things that depend on it |
|---|---|---|
| Org unit | The end is rejected | The end is rejected while child units or assignments hold on or after the date |
| Assignment | The end is rejected | Nothing depends on an assignment yet |
| Employment | They are removed | Its terms and its assignments end on the date. Assignments that would start later are removed |
| Reporting line | A line has no changes | It ends when its assignment or its employment ends |

An org unit and an assignment reject the end, so that a planned change is not lost by accident.
An employment removes the stored future, because a person who leaves has no future terms.

The end of an assignment or an employment can move earlier or later.
`DELETE .../end` removes it.
An org unit cannot change its end yet.

Checked by `OrgUnitsIT.endsALeafAndKeepsItsHistory`, `OrgUnitsIT.rejectsABadEnd`, `AssignmentsIT.anOrgUnitWithAssignmentsCannotEndBeforeThem`, `AssignmentsIT.changesAJobTitleOnADateAndEndsTheAssignment`, and `EmploymentsIT.leavingEndsTheTermsAndTheAssignments`.

## Managers and reports

A reporting line links an assignment to a manager for a period.
A solid line is the main manager. An assignment has at most one at a time.
A dotted line is a further manager, for example of a project.

When an assignment has no solid line on a date, the manager is derived from the tree.

- A member reports to the head of the org unit.
- A head reports to the head of the nearest unit above that has one.
- A member of a unit with no head does the same.
- Two heads of one unit are not each other's managers.

`GET /users/{userId}/managers` and `GET /users/{userId}/reports` use these rules.
Each entry says whether it is stored or derived.

Checked by `ReportingLinesIT.derivesManagersAndReportsFromTheHeadsOfTheTree` and `ReportingLinesIT.storedLinesChangeTheAnswerFromTheirDate`.

## Rules that hold on every date

- An org unit has one set of details and at most one parent in a hierarchy.
- A parent exists on every date of its child.
- No unit is below itself.
- An employment has one set of terms, with no gaps.
- An assignment lies inside its employment and inside the life of its org unit.
- An employment has at most one primary assignment.

The application checks these rules on every write.
On PostgreSQL the database checks the overlap rules again.

Checked by `OrgUnitsIT.rejectsAMoveBelowItselfAndOtherBadMoves`, `AssignmentsIT.rejectsBadRequests`, `AssignmentsIT.anEmploymentHasOnePrimaryAssignmentAtATime`, and `SchemaIT`.

## Many changes on one date

`POST /reorganizations` applies many org unit changes on one date in one transaction.
When one step fails, nothing is written.

Checked by `ReorganizationIT.appliesAReorganizationUnderAChangeOrder` and `ReorganizationIT.aFailedStepWritesNothing`.

## The change log and change orders

Every successful write adds one entry to the change log.
A write with the query parameter `changeOrder` belongs to that change order.
The change order has the approver and the effective date.

So these two reads answer who approved a change.

- `GET /change-log?resourceType=&resourceId=` lists the writes to one resource. Each entry has the ID of its change order.
- `GET /change-orders/{id}/changes` lists the writes of one change order.

Entries are only added. A correction of a mistake adds a new entry and keeps the old one.
So the log shows what the system said at any earlier time, even after the rows were corrected.

The application does not compare the dates of a write with the effective date of its change order.
The client keeps them the same.

Checked by `ChangeLogIT.listsTheWritesOfAChangeOrderInTheOrderTheyHappened` and `ChangeLogIT.listsTheWritesToOneResourceWithAndWithoutAChangeOrder`.

## What is removed for good

- Removal of a realm removes all its rows and its change log.
- `DELETE /users/{userId}/data` erases everything about one user.

Removal of a user from Keycloak removes nothing that held.
It ends the open employment of the user on that day, and the history stays readable.

Checked by `RealmRemovalIT`, `UserRemovalIT.removingAUserKeepsTheirHistoryAndEndsWhatIsOpen`, and `UserRemovalIT.erasingAUserRemovesEverythingAboutThemAndNothingElse`.

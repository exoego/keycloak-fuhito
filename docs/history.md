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
| Org unit | Its details with its code, and its parent in a hierarchy |
| Employment | The employment itself, and its terms |
| Assignment | Its details |
| Reference item | Its names |

Each resource keeps its ID through all changes.
The code of an org unit is part of its details, so a reorganization can give a unit a new code on a date.
No two org units have the same code on the same date. A code that one unit gives up on a date is free for another unit from that date.
The ID is the key that never changes. So is `source`, the name of the system that made the resource. The IDs that systems have for a resource have no history. A write replaces them.

### Reference items keep their old names

A reference item, such as a job title or a unit type, keeps its old names for old dates.
Only the names have valid periods. The kind and the code never change. A `PUT` with another kind or code fails with 400 and `invalid.value_fixed`. The display order, the attributes, `active`, and the marks have no dates.
The item itself has no dates either. Data of any date may use it.

- `POST /ref-items` takes `validFrom`. Without it, the first names hold for every date.
- `PUT /ref-items/{id}` with `validFrom` keeps the old names for the dates before it. A date inside stored names, a date in the past, and the first date of stored names follow the rules of a change on a date below.
- `PUT /ref-items/{id}` without `validFrom` changes the names of today. This fits a fix of a typo.
- A date before the first names of an item gets its first names. A write on such a date gives names from that date until the first names start.
- Names that are the same as the names of the date make no new period. Another primary language is a change of the names.
- `GET /ref-items`, `GET /ref-items/{id}`, the role names of `GET /unit-roles`, and the CSV file of `GET /imports/csv/ref-items` show the names of their date. `GET /ref-items/count` takes no date, because every item has names on every date.
- `GET /ref-items/{id}/history` lists the names of every period, oldest first.
- A write of names counts up the version of the item, and adds an entry to the change log.

A new code is still the way when the meaning changes, for example when one job title splits into two. Add a new item, move the data to it from the date, and deactivate the old item.

Checked by `RefItemsIT.aRenameFromADateKeepsTheOldNameForEarlierDates`, `RefItemsIT.createsAnItemWithNamesFromADate`, and `UnitRolesIT.listsTheRolesOfADateAcrossTheRealmInPagesByIdWithTheirNames`.

## Reading

- Every read of a tree or a list takes `asOf`. It returns what is true on that date.
- The default of `asOf` is today. The realm decides what today is with its time zone and its day start. An org unit can set its own. See [keycloak-integration.md](keycloak-integration.md).
- `GET /org-units/{id}/history` returns every period of an org unit. `GET /ref-items/{id}/history` returns every period of the names of a reference item.
- `GET /employments/{id}` and `GET /assignments/{id}` return every period of their resource.
- `GET /people/{id}/history` returns every employment and assignment of a person. `GET /users/{userId}/history` does the same for the person of a user.

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

### The parent and the places in a change of the details of an org unit

The details and the parent of an org unit change on dates of their own.
`PUT /org-units/{id}` replaces the details from `validFrom`, and `PUT /org-units/{id}/parent` moves the unit.
A read has the parent of its date, and the work location and the cost center that apply on it. The details of the read can start before a move or before a change of a unit above.
So `PUT /org-units/{id}` takes a `parentId`, an `effectiveWorkLocation`, and an `effectiveCostCenter` that the unit has on any date of the details that it replaces. These dates run from `validFrom` to the end of the stored details of that date.
Another value fails with 400 and `invalid.value_fixed`. For a unit with no parent on those dates, such as a root or a rollup unit, `parentId` must be null or left out.

Checked by `FixedValuesIT.aPutOfTheDetailsOfAnOrgUnitTakesOnlyAParentAndPlacesOfTheDetailsThatItReplaces`.

## An end

An end says "from this date, this no longer holds".
The rules differ by resource, because the right answer to "what about the stored future" differs.

| Resource | Stored changes on or after the end date | Things that depend on it |
|---|---|---|
| Org unit | The end is rejected | The end is rejected while child units, assignments, or roles over it hold on or after the date |
| Assignment | The end is rejected | Nothing depends on an assignment yet |
| Employment | They are removed | Its terms, its assignments, and its roles over org units end on the date. Those that would start later are removed |
| Reporting line | A line has no changes | It ends when its assignment or its employment ends |
| Role over an org unit | A role has no changes | It ends when its employment ends |

An org unit and an assignment reject the end, so that a planned change is not lost by accident.
An employment removes the stored future, because a person who leaves has no future terms.

The end of an org unit, an assignment, or an employment can move earlier or later.
`DELETE .../end` removes it.

- An earlier end of an org unit needs the same as a first end. The parents that ended with it end on the new date.
- A later end of an org unit, or no end, lengthens its last details and the parents that ended with it. Each parent must exist on the new dates.
- The end of an org unit that other units follow after a merger or a split does not move. It fails with `org_unit.has_successors`.
- The end of an employment or an assignment that another one continues, as after a transfer or a move, does not move past the start of that one. A later end and `DELETE .../end` then fail with `employment.continues_holds` or `assignment.continues_holds`. An end on that start or before it is fine, and so is a gap. The import has the same rule.

## A delete and an end

`DELETE /org-units/{id}`, `DELETE /employments/{id}`, and `DELETE /assignments/{id}` remove a resource with every period of it.
A read of a past date then finds nothing, as if the resource had never been there.
An end keeps the past. A read of a date before the end still finds the resource.

| | End | Delete |
|---|---|---|
| Path | `PUT .../end` | `DELETE` on the resource |
| The past | Stays readable | Gone |
| When it works | The rules of an end above | Only when nothing refers to the resource |
| Use | A department that closes, a person who leaves, a move to another unit | A resource that should never have been stored, such as a test or a typo |

- A delete works only when nothing refers to the resource. Otherwise it fails with `org_unit.in_use`, `employment.in_use`, or `assignment.in_use`, and `uses` names what refers to it.
- An org unit must have had no children, and no assignment, employment, role, or succession may name it. It must have no link to a Keycloak organization.
- An employment must have no assignment and no role over an org unit, and no employment may continue it.
- An assignment must have no reporting line, and no assignment may continue it.
- The IDs that other systems have for the resource go with it.
- The change log keeps every entry of the resource, and gets one for the delete. A product that copied the resource learns of it there.

Checked by `RemovalsIT.aResourceMadeByMistakeGoesWhenNothingRefersToIt`.

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
- An assignment in a lateral unit gets no derived manager, and the head of a lateral unit is nobody's derived manager. A stored line still works.
- A rollup unit has no parent in the default hierarchy, so its head gets no derived manager. Its head is the derived manager of its other members.

A head can be away, and another person can stand in.

- A head is absent on a date when the terms of the employment have the status `ON_LEAVE` or `SUSPENDED`. An absent head is nobody's derived manager, and reports to the head above.
- An acting head is an assignment with `head=true` and an assignment kind with the mark `actingHead`. The mark goes on the kind, not on a job title. A title such as "deputy manager" means different things in different places.
- The heads of a unit are its present acting heads. When there is none, they are its present heads. When there is none either, the unit has no head on that date.
- So an acting head leads while its assignment holds, also when the status of the head stays `ACTIVE`, for example during a sick leave that is not a status of the employment. The other heads report to the head above.
- Stored lines do not change. A line to a manager on leave stays.

These rules cover a head who is away for long, with a leave of the employment or an acting head with dates. A short absence, such as a holiday or a business trip, changes no data. The approval workflow of the application handles it, for example with a delegate.

`GET /people/{id}/managers` and `GET /people/{id}/reports` use these rules.
Each entry says whether it is stored or derived.

Checked by `ReportingLinesIT.derivesManagersAndReportsFromTheHeadsOfTheTree`, `ReportingLinesIT.storedLinesChangeTheAnswerFromTheirDate`, `ReportingLinesIT.anActingHeadLeadsTheUnitWhileItHolds`, and `ReportingLinesIT.theHeadOfARollupUnitLeadsItsMembersAndHasNoManagerAbove`.

## Rules that hold on every date

- An org unit has one set of details and at most one parent in a hierarchy.
- A parent exists on every date of its child.
- No unit is below itself.
- A rollup unit has no parent and no child in the default hierarchy.
- An employment has one set of terms, with no gaps.
- An assignment lies inside its employment and inside the life of its org unit.
- An employment has at most one primary assignment.

The application checks these rules on every write.
A restore checks them too, before it writes.
On PostgreSQL the database checks the overlap rules again.

Checked by `OrgUnitsIT.rejectsAMoveBelowItselfAndOtherBadMoves`, `RollupUnitsIT.aRollupUnitGetsNoParentOrChildInTheDefaultHierarchy`, `AssignmentsIT.rejectsBadRequests`, `AssignmentsIT.anEmploymentHasOnePrimaryAssignmentAtATime`, and `SchemaIT`.

## Many changes on one date

`POST /reorganizations` applies many org unit changes on one date in one transaction.
When one step fails, nothing is written.
Each operation reads only its own fields. A step that gives another field fails with `reorganization.field_unused` before any step runs, so no value is dropped with no word.
A step that names its unit by `id` and by `unitRef`, or its parent by `parentId` and by `parentRef`, fails with `invalid.twice` before any step runs too.

Checked by `ReorganizationIT.appliesAReorganizationUnderAChangeOrder`, `ReorganizationIT.aFailedStepWritesNothing`, `UnusedFieldsIT.aReorganizationStepWithAFieldThatItsOperationDoesNotReadFailsAndWritesNothing`, and `TwoWaysIT.aStepThatNamesItsUnitOrItsParentByIdAndByRefFailsAndWritesNothing`.

### A merger and a split

The step `succeed` ends an org unit on the date and records the units that follow it.
It names those units in `successorIds`, or in `successorRefs` for units that an earlier step of the same request creates.

- One unit that follows is a succession. A split names several.
- A merger is a step for each old unit, and each names the same new unit.
- The kind is not stored. The links already say it.
- Each unit that follows must exist on the date.
- The old unit ends the same way as with the step `end`. The units below it and the people in it must move first.
- `GET /org-units/{id}/succession` lists the units before a unit and the units after it. A report across a reorganization follows a unit with it.
- The change log has two entries for the old unit. One has the path `.../end`, and one has the path `.../succession`.

An end cannot be taken back, so a link never points to a unit that exists again.

Checked by `ReorganizationIT.aMergerLinksTheOldUnitsToTheNewOne`.

## A move to another org unit

An assignment never changes its org unit. A move is the end of one assignment and the start of another.
`POST /assignments/{id}/move` does both in one write.

- The new assignment starts on the date that the old one ends on, so the person is in one place on every date.
- It lasts as long as the old one would have, and names the old one in `continuesAssignmentId`.
- It keeps the occupation, the kind, the primary flag, the allocation, and the attributes. It keeps the job title unless the request gives one.
- The head flag, the display order, the work location, and the cost center belong to the old org unit, so they are not kept. The request can give a new work location and cost center. Without them, the new assignment takes the ones of its org unit.
- The reporting lines of the old assignment end with it. The new assignment gets its manager from the head of its org unit, until a line is stored.
- A change of the old assignment that is stored for the date or later rejects the move.

Checked by `AssignmentsIT.aMoveTakesAPersonToAnotherOrgUnitInOneWrite`.

## A promotion

A change of terms and a change of the details of an assignment each replace all values.
`POST /employments/{id}/promotion` is for the common case that only the grade, the job title, or the head flag changes.

- The request has `validFrom`, and `grade`, `jobTitle`, or `head`, or several of them.
- The grade goes to the terms of the employment. The job title and the head flag go to one assignment.
- That assignment is the one that `assignmentId` names. With none, it is the primary assignment of the employment on the date.
- `assignmentId` names an assignment of the employment that holds on the date. Another ID fails with `employment.assignment_not_on`.
- A request that gives `assignmentId` with no `jobTitle` and no `head` fails with `employment.promotion_assignment_unused`. The promotion would not read the assignment.
- Every value that the request does not name stays as it is on the date.
- Both writes are one transaction. When one fails, nothing is written.
- The change log has one entry for the employment with the path `.../promotion`, and one for the assignment.

Checked by `StaffIT.promotesAPersonInOneWrite` and `UnusedFieldsIT.aPromotionWithAnAssignmentAndNoJobTitleOrHeadFailsAndWritesNothing`.

## A leave and a return

A leave of absence is the status `ON_LEAVE` in the terms of an employment.
Two operations change only that status, and keep every other value of the terms.

- `POST /employments/{id}/leave` has `validFrom`. The person is on leave from that date.
- The leave can have a `reason`. It is the code of a reference item of the kind `LEAVE_REASON`.
- With `validTo`, the status of before the leave comes back on that date, in the same write. Leave it out when the end is not known yet.
- `POST /employments/{id}/return` has `validFrom`. The person is active from that date, with no leave reason. A return that was stored for a later date stays, and changes nothing more.
- Only terms with the status `ON_LEAVE` can have a leave reason. Other terms with one fail with `employment.leave_reason_not_on_leave`. This holds for `PUT /employments/{id}/terms` and the import too.
- A leave fails with `employment.already_on_leave` when the person is on leave on the date.
- A leave fails with `employment.terms_change_stored` when a change of terms is stored for a later date. The leave would end there without a word.
- A return fails with `employment.not_on_leave` when the person is not on leave on the date.

An acting head is not part of these operations.

Checked by `StaffIT.aLeaveAndAReturnChangeOnlyTheStatus`.

## A person who comes back

A person who left has an employment with an end. A return is a new employment of the same person.
`POST /staff` starts it and adds its first assignment in one write. It takes `personId`, or `userId` for a person with a Keycloak user. It also takes both when they name the same person, so the two IDs of a read of the old employment work.

- The old employment and its assignments stay as they are. `GET /users/{userId}/history` and `GET /people/{id}/history` list both employments.
- The new employment can have the staff number of the old one. Another person cannot take it at the same employer.
- The new employment does not name the old one in `continuesEmploymentId`. That field is for a transfer with no gap, where the years of service go on.

Checked by `EmploymentsIT`, `MembershipIT`, and `TwoWaysIT.aPersonOrAManagerByBothIdsOfOnePersonWorks`.

## The change log and change orders

Every successful write adds one entry to the change log.
An entry says who wrote, when, which resource, which operation, and which change order.
It does not keep the state of the resource. A reader that needs the state reads the resource again.
A write with the query parameter `changeOrder` belongs to that change order.
The change order has the approver and the effective date.

So these two reads answer who approved a change.

- `GET /change-log?resourceType=&resourceId=` lists the writes to one resource. Each entry has the ID of its change order.
- `GET /change-orders/{id}/changes` lists the writes of one change order.

### Announcing a change order

A planned transfer is often secret until a set day. That day is not the effective date.
A change order has `announcedAt` and `announcedByUserId` for it. They are absent until someone records the announcement.

- `POST /change-orders/{id}/announce` sets `announcedAt` to now and `announcedByUserId` to the caller.
- No request can set them another way, and none can set a time in the future. A second call fails with `change_order.already_announced`.
- It is a fact and not a planned date, on purpose. With a planned date, an announcement that was put off and not corrected would show the plan on the old date. With a fact, a step that someone forgot keeps the plan hidden. An application that announces on a set day calls the operation on that day.
- The extension hides nothing with these fields. The callers of the API are trusted services, and the application that shows the data decides who may see what.

An application can hide planned changes like this.
Each entry of the change log has the ID of its change order. A row is hidden when it starts in the future and its change order has no `announcedAt`.

### Following the changes

Each entry has a `sequence`. It is the position of the entry in the change log of its realm, counted from 1.

`GET /change-log?after={sequence}&max=` lists the writes to every resource after that number, oldest first.
So another system can ask what changed since its last read.

1. Read with `after=0`, or with no `after`.
2. For each entry, read the resource again to get its state. A deleted resource answers 404. Keep the `sequence` of the last entry.
3. Read again with `after` set to that number. A read with fewer entries than `max` has reached the end.

`max` is 100 by default and 1000 at most. `resourceType` and `resourceId` narrow the list, each alone or both.

The entries of a realm become readable in the order of their numbers.
So a reader that goes on after the last number it saw misses no entry.
For this, the writes to one realm record their entries one after another.

The numbers have no gaps.
An entry holds only IDs, so a mask and an erase do not change or remove earlier entries.
The erase adds one entry of the type `FUHITO_PERSON_DATA` with the operation `DELETE`. Its resource ID is the ID of the person.
It stands for every row that the erase removed, also the reporting lines where the person is the manager. A reader can use it to remove its own copy of those rows.
A mask adds an entry of the same type with the operation `UPDATE`. A reader can use it to clear the personal data in its own copy.

Checked by `ChangeLogIT.followsEveryWriteOfTheRealmAfterASequenceNumber`, `ChangeLogIT.eachRealmCountsItsOwnWritesFromOneWithoutGapsUnderParallelWrites`, `ChangeLogIT.anEntryHasNoStateOfTheResource`, and `UserRemovalIT.aMaskAndAnEraseKeepTheEarlierEntriesAndAddTheirOwn`.

### Learning when a dated change takes effect

A change with a future date is in the change log on the day it is stored.
Nothing is written on the day it takes effect. So the change log alone does not tell that a person moves today.

`GET /effective-changes?from=&to=` lists the resources with a period that starts or ends in a range of dates.
An entry has the date, the type of the resource, its ID, and its path. It says that something about the resource starts, changes, or ends on that date.
Read the resource with `asOf` to see what.

- `from` is today by default, in the time zone of the realm. `to` is the day after `from` by default. The range does not include `to`.
- The resources are org units, employments, assignments, reporting lines, roles over org units, and qualifications. A new parent of an org unit counts as a change of the org unit.
- The entries come from the rows as they are now. A change that was removed before its date is not listed, and a change that was moved is listed on its new date.
- A resource comes once for each date, also when several of its details change on that date.
- The list is read in pages with `first` and `max`.

So a system can ask once a day what takes effect today. After a stop, it asks for the days that it missed.
An org unit with its own time zone starts its day at another moment than the realm. A system that needs that moment asks for the date of that unit.

Checked by `EffectiveChangesIT`.

Entries are only added. A correction of a mistake adds a new entry and keeps the old one.
So the log shows who changed which resource and when, even after the rows were corrected.
It does not keep the values from before a correction. Only the corrected values stay.

The application does not compare the dates of a write with the effective date of its change order.
The client keeps them the same.

Checked by `ChangeLogIT.listsTheWritesOfAChangeOrderInTheOrderTheyHappened` and `ChangeLogIT.listsTheWritesToOneResourceWithAndWithoutAChangeOrder`.

## What is removed for good

- Removal of a realm removes all its rows and its change log.
- `DELETE /people/{id}/data` erases everything about one person. The change log keeps its entries, which hold only IDs.

Removal of a user from Keycloak removes nothing that held.
It ends the open employment of the user on that day, and the history stays readable.

Checked by `RealmRemovalIT`, `UserRemovalIT.removingAUserKeepsTheirHistoryAndEndsWhatIsOpen`, and `UserRemovalIT.erasingAUserRemovesEverythingAboutThemAndNothingElse`.

# Performance

This document has the numbers of a run with a large realm, and says how to repeat it.
The numbers show which reads grow with the size of a realm. They are not a promise, because they depend on the machine.

## How to measure

`scripts/bench/run.sh` starts PostgreSQL and Keycloak with the extension in Docker, fills one realm, and measures the reads. Then it measures three new work locations of org units, and the fill and the clear of an attribute, as the sections below say.
It needs Docker, curl, and jq. Run it from the root of the project. It is not a check of CI.

`scripts/bench/seed.sql` fills the realm with SQL, because the API would take too long for this many rows.
`PEOPLE` and `UNITS` set the size. The defaults are the size of the table below.

| What | Rows |
|---|---|
| Org units, in four levels below one company | 5,001 |
| Versions of org units | 6,001 |
| People, each with one employment | 50,000 |
| Assignment rows. Three people in ten moved once | 65,000 |
| Entries of the change log | 200,000 |

The database writes the plan of every statement that takes 20 ms or more to its log.
So a slow read can be looked at with `docker logs fuhito-bench-pg`.
The database also loads `pg_stat_statements`, which counts the statements of a run. The timer of the projection is off, so only the calls of the script rebuild it.
`JAR` names a jar that is built already, as a path from the root of the project. Then the script does not build one.

## Results

Run on 2026-11-15 at commit `4346339`, with Keycloak 26.8.2 and PostgreSQL 18, on a laptop with an Apple M4 Pro and 48 GB of memory.
Each number is the best of five requests, measured by curl on the same machine.

| Read | Path | ms |
|---|---|---|
| Org units, first page | `/org-units?max=100` | 82 |
| Org units, last page | `/org-units?first=4900&max=100` | 95 |
| Roots | `/org-units?roots=true` | 6 |
| Legal entities | `/org-units?legalEntity=true` | 6 |
| One org unit | `/org-units/u-4321` | 5 |
| Children of a division | `/org-units/u-1/children` | 5 |
| Subtree of the company, first page | `/org-units/u-0/subtree?max=100` | 81 |
| Subtree of a division, 111 units | `/org-units/u-1/subtree?max=1000` | 8 |
| Path of a team | `/org-units/u-4321/path` | 5 |
| Members of a team | `/org-units/u-4321/members` | 9 |
| Members below the company, first page | `/org-units/u-0/members?includeDescendants=true&max=100` | 128 |
| Members below the company, page 400 | `/org-units/u-0/members?includeDescendants=true&first=40000&max=100` | 303 |
| Members below a division, 1,000 people | `/org-units/u-1/members?includeDescendants=true&max=1000` | 68 |
| People, first page | `/people?max=100` | 3 |
| People, page 490 | `/people?first=49000&max=100` | 9 |
| Assignments of a person, with the path | `/people/p-12345/assignments?includePath=true` | 12 |
| History of a person | `/people/p-12345/history` | 6 |
| Managers of a person | `/people/p-12345/managers` | 6 |
| Reports of the head of the company | `/people/p-0/reports?max=100` | 6 |
| Reports of the head of a division | `/people/p-1/reports?max=1000` | 4 |
| Change log, 1,000 entries from the start | `/change-log?max=1000` | 9 |
| Change log, 1,000 entries from the middle | `/change-log?after=100000&max=1000` | 7 |
| Change log of one resource | `/change-log?resourceType=FUHITO_ASSIGNMENT&resourceId=a-777` | 4 |
| Effective changes of one day | `/effective-changes?from=2024-04-01` | 18 |
| Effective changes of five years, first 1,000 | `/effective-changes?from=2020-01-01&to=2026-01-01&max=1000` | 234 |

### After the indexes on the realm and the dates

The tables of employments, terms, assignments, qualifications, and reporting lines got an index on the realm and `VALID_FROM`, and one on the realm and `VALID_TO`.
The same run then gave these numbers. The other reads did not change.

| Read | Before in ms | After in ms |
|---|---|---|
| Effective changes of one day | 18 | 11 |
| Effective changes of five years, first 1,000 | 234 | 46 |

## A realm ten times as large

`PEOPLE=500000 UNITS=50000 scripts/bench/run.sh` fills a realm with 50,001 org units, 500,000 people, 650,000 assignment rows, and 2,000,000 entries of the change log.
Run on 2026-11-15 at commit `cd203c4`, on the same machine.

The reads about one org unit, one person, one team, or one page of the change log stayed between 3 and 10 ms. These grew.

| Read | ms | ms with the two indexes below |
|---|---|---|
| Org units, first page | 1,255 | 333 |
| Subtree of the company, first page | 1,245 | 342 |
| Subtree of a division, 1,000 units | 28 | 17 |
| Members below the company, first page | 1,525 | 595 |
| Members below the company, page 4,000 | 3,060 | 3,006 |
| Members below a division, 1,000 people | 126 | 82 |
| People, page 4,990 | 60 | not measured again |
| Effective changes of a day on which 150,000 people moved | 202 | 67 |

The plan of the tree walk showed where the time went. For each org unit, the walk looked up the version of the date through the exclusion constraint, which is a GiST index. That took about a second for 50,000 org units.
So the versions got a plain index on the org unit and `VALID_FROM`, and the parent links got one on the hierarchy, the child, and `VALID_FROM`.

What is still slow at this size, and why.

- The whole tree costs about 330 ms for each page. The database walks all 50,000 org units and orders them before it cuts the page.
- The members below the whole company cost about 600 ms for the first page, and 3 seconds for a late page. The database orders all 500,000 assignments by the place of their org unit in the tree, and then counts past the skipped rows.

A reader of a late page pays for all pages before it. A cursor that says "go on after this row" would make every page cost as much as the first one.

## What changed after these runs

- The read of the whole tree, `GET /org-units` with every org unit, is gone. `GET /org-units` now lists the roots. The rows "Org units, first page" and "Org units, last page" above are from before that, and the script no longer measures them. A subtree of a root still walks the whole tree.
- Later, `GET /org-units` lists every org unit again, with `roots=true` for the roots. The list is in the order of siblings, not in tree order, so it does not walk the tree. The script measures its first page and its last page again. The rows above are still from the tree order.
- The two plain indexes were dropped later. The unique keys `UK_FUHITO_ORG_UNIT_VERSION_FROM` and `UK_FUHITO_ORG_UNIT_LINK_FROM` have the same columns, and they are B-tree indexes too. Every database has them, also one without `btree_gist`. A run with 50,001 org units and 500,000 people before and after the drop gave the same times within 5% for every read. The unique key of the versions took the 1.5 million lookups that the plain index had served. The plain index of the links had served none.
- The number 333 ms for the whole tree with the two indexes does not hold in general. In a second run the planner still chose the GiST index of the exclusion constraint, and the read took 1.2 seconds. It chose the plain index only after the index was built again. Which one it takes depends on the sizes of the two indexes. The section below has the walk that was meant not to depend on that choice.
- Later, the GiST index of the exclusion constraint got the condition `daterange(VALID_FROM, VALID_TO) IS NOT NULL`. PostgreSQL takes an index with a condition only for a read whose own conditions make it true, and no read names that range. So every lookup of the versions of an org unit takes `UK_FUHITO_ORG_UNIT_VERSION_FROM`, with old and with fresh statistics, and the choice no longer depends on the sizes. A range is never null, so the constraint still covers every row. The condition `ID IS NOT NULL` of the GiST index of the terms of employments would not do here. The names join the versions by the ID, and that join makes it true.
- Later, the GiST index of the exclusion constraint on the links got the same condition. With fresh statistics, PostgreSQL took it for every lookup of the parent of an org unit, also in each step of a walk up. With a tree of 5,000 org units in a new realm, the members below the root of a second hierarchy by a work location took 1,151 ms with the GiST index and 214 ms with `UK_FUHITO_ORG_UNIT_LINK_FROM`. Now the unique key serves every lookup of links by the hierarchy and the child. A read of the links by the child alone, such as the history of an org unit, takes the unique key with a skip scan, which PostgreSQL has since version 18, or reads the table.

## The walk down a subtree

Three ways to find the version of each org unit in the walk were measured, with 50,001 org units and 500,000 people.

| Read | Lookup by org unit and dates | Dates filtered once, then joined | Latest version, then by ID |
|---|---|---|---|
| Subtree of the company, first page | 1,245 | 237 | 424 |
| Subtree of a division, 1,000 units | 28 | 57 | 39 |
| Members below the company, first page | 1,525 | 494 | 763 |
| Members below a division, 1,000 people | 126 | 153 | 121 |

- The first column is the walk of before. The database served the lookup with the GiST index of the exclusion constraint. With the plain index it took 333 ms for the company, but nothing made the database take that one.
- The second filters the versions and the links of the date once and joins them in each step. It is the fastest for the whole company. But it reads every org unit of the realm also for a small subtree.
- The third finds the latest version that started, and then reads it by its ID. The cost follows the size of the subtree.

The extension uses the third. A subtree of a part of the organization is the usual read, and a read of the whole company is rare.

The third was meant to keep the database off the GiST index, because only a plain index gives the latest version without a sort. But the GiST index could still serve the first lookup, with a sort for each org unit, and PostgreSQL took it in some databases. With a tree of 5,000 org units in a new realm, the first page took 154 ms with the GiST index and 58 ms with the plain index. Since the GiST index has its condition, the plain index serves this lookup in every database.

## Late pages with a cursor

The list of people and the members of an org unit give a cursor for the next page in the header `Link`.
Measured with 50,001 org units and 500,000 people.

| Read | With `first` in ms | With the cursor in ms |
|---|---|---|
| People, the page after 499,000 people | 60 | 3 |
| Members below the company, the page after 400,000 members | 3,300 | 731 |
| Members below the company, the first page | 820 | the same read |

With the cursor, a late page of the members costs as much as the first page. It does not get cheaper than that, because every page still walks the tree below the org unit.

## The filter by work location and cost center

`GET /org-units/{id}/members` takes `workLocation` and `costCenter`. The value that applies to a member is the one of the assignment, else of its org unit, else of the nearest unit above in the default hierarchy.
Three ways to find it in the database were measured on the realm of the first table, with the work locations and cost centers that `scripts/bench/seed.sql` gives it. The company has a work location that applies almost everywhere.
Run on 2026-11-17 on top of commit `ec5cc41`, on the same machine. Each number is the best of seven requests, in ms.

| Read | No filter | Down from the units with the value | Up from each unit of the read | Down the subtree with the values |
|---|---|---|---|---|
| Members of a team, by the work location of the company | 12 | 105 | 16 | 15 |
| Members below a division, 1,000 people, by the work location of the company | 68 | 163 | 74 | 60 |
| Members below the company, first page, by the work location of the company | 123 | 215 | 409 | 116 |
| Members below the company, first page, by the work location of one division | 123 | 107 | 412 | 107 |

- The first way starts at the units that have the value, and walks down into the children with no value of their own. It walks every unit where the value applies, also for a read of one team.
- The second walks up from each unit of the read until a unit has a value. For the whole company that is about three steps for each of its 5,000 units.
- The third gives each unit the values of its parent while it walks down the subtree. That is right only in the default hierarchy, because the values come down that one.

The extension uses the third for a read below a unit in the default hierarchy. A read of one unit walks up from that unit.
A read below a unit in another hierarchy uses the second, because the parents there are not the ones that give the values. A project with 100 teams below it took 63 ms with the filter and 66 ms without. A copy of the whole tree as another hierarchy took 434 ms for the first page, against 123 ms without the filter.
No way looks up an org unit by its work location or its cost center, so these columns have no index.

## The projection into Keycloak

`BATCH=1 SYNC=0 scripts/bench/run.sh` also makes one Keycloak user for each person, links the company to one Keycloak organization, and runs `POST /projection/rebuild` twice.
The realm has 50,000 people and 5,000 org units. The first run makes 5,000 groups, 49,990 memberships of groups, and 50,000 members of the organization.

Each version keeps the changes of the versions on its left.

| Run | One transaction | Pieces of 100 org units | Memberships of a piece read at once | Memberships written in transactions of 50 |
|---|---|---|---|---|
| First run | fails | 226 s | 245 s | 96 s |
| Nothing changed | not run | 14 s to 26 s | 3.4 s | 3.5 s |

What the runs showed.

- One transaction, with one check of membership for each user, does not work. Keycloak ends a transaction after 300 seconds. Before that limit, the list of IDs after `in` passed the 65,535 values that PostgreSQL takes in one statement.
- A list of IDs is now one array, and the SQL is `id = any(?)`. That removed the limit. It did not change the times.
- With nothing to change, the time went into one question to Keycloak for each user. One query for each piece now reads who is in which group.
- In the first run, the time goes into the writes. Keycloak flushes after each change of a membership, and a flush looks through everything that the transaction loaded. Small transactions keep each flush small.
- No statement of a rebuild took 20 ms or more in the database. The time is in Keycloak and in the trips to the database.

The first run still costs about 1 ms for each write. The writes go through the API of Keycloak, so that its caches and its events stay right.

### The writes of one user in one transaction

A profile of a first run with Java Flight Recorder showed where the time went.

- 39% of the samples of the rebuild were in loads of users. Keycloak loads a user again in each transaction that changes it. In a realm with organizations, each load asks the database for the organizations of the user.
- The membership in the organization and the membership in a group of one user were written in two transactions.
- Keycloak flushed after each new membership.

| Write of a new member | Before | After |
|---|---|---|
| Transactions for the organization and the group of one user | 2 | 1 |
| Flushes of new memberships | 1 for each membership | 1 at the end of each transaction, in the batch mode of Keycloak |

The batch mode is the one that the import of a realm in Keycloak uses. The events and the user cache of Keycloak work as before. Writing the rows with SQL would be faster, but the cache of organization members and the workflows of Keycloak listen for the events of new members, so that is not done. See #438.

Measured on 2026-11-16 with 50,000 people, at the same commit. One run each.

| Run | Before | Batch mode only | One transaction for each user only | Both |
|---|---|---|---|---|
| First run | 95.9 s | 90.4 s | 84.4 s | 79.4 s |
| Nothing changed | 2.8 s | 3.2 s | 3.2 s | 3.1 s |

A second run before the change took 94.8 s and 3.5 s, so a run with nothing to change moves between about 3 and 3.5 seconds from run to run. The extension uses both changes.

### The writes of different users at the same time

Half of the samples of the profile above waited for the database. So the writes of members of a piece of units now run on 4 threads, each piece in a session and a transaction of its own. The writes of one user stay in one transaction, so two threads never write for the same user.

Measured on 2026-11-16 with 50,000 people, at the same commit. One run each.

| Run | Before | After |
|---|---|---|
| First run | 78.4 s | 62.1 s |
| Nothing changed | 3.4 s | 3.3 s |

- The Docker of the run had 2 CPUs for Keycloak and PostgreSQL together. A machine with more CPUs was not measured.
- The groups of one level of the tree were also made at the same time in one run. It took 62.1 s too, so the groups are still made one piece after another.

## The sync from user attributes

`BATCH=1 scripts/bench/run.sh` also runs `POST /attribute-sync/run` twice. Each of the 50,000 users has a department. The first run adds 50,000 assignments.

Each version keeps the changes of the versions on its left.

| Run | One transaction | Pieces of 100 users | Attributes of all users read at once |
|---|---|---|---|
| First run | fails | fails | 84 s |
| Nothing changed | fails | fails | 16 s |

What the runs showed.

- The time went into the read of the users, before any write. The API of Keycloak loads one user at a time, and each load first looked through every user that the transaction had loaded. The run passed the 300 seconds of a transaction in that read alone.
- One query on the tables of Keycloak now reads the attributes that the sync needs.
- The pieces keep each transaction short. A first run writes an assignment, and often a person and an employment, for each user through the services.
- With nothing to change, a run still asked for the employments and the reporting line of each user. That was the 16 seconds. The next section is about that.

### The reads of a piece at once

A run works in pieces of 100 users. These are the reads of one piece when nothing changes.

| Read | Before | After |
|---|---|---|
| The employments of the users | 100 queries, and one more for the person of each employment | 1 query |
| The reporting lines of their assignments | 100 queries, and one more for the manager of each line | 1 query |
| Whether their managers exist | 1 call of the API of Keycloak for each user with a manager | 1 query on the tables of Keycloak |

A manager who is not in the tables of Keycloak is still looked up through the API, because a user storage provider can hold that user.

Measured on 2026-11-16, before and after the change at the same commit. One run each.

| People | Run | Before | After | Time saved |
|---|---|---|---|---|
| 50,000 | First run | 96 s | 79 s | 18% |
| 50,000 | Nothing changed | 19.5 s | 3.7 s | 81% |
| 500,000 | First run | 919 s | 699 s | 24% |
| 500,000 | Nothing changed | 183 s | 33 s | 82% |

The users of the seed have no manager, so these runs did not measure the third read.

## The batch jobs in a realm ten times as large

`PEOPLE=500000 UNITS=50000 BATCH=1 scripts/bench/run.sh`. One run each.

| Job | First run | Nothing changed |
|---|---|---|
| Projection | 1,793 s | 43 s |
| Sync from user attributes | 933 s | 186 s |

What the runs showed.

- The times grow about in line with the number of people. A first run is one write at a time through the API of Keycloak or through the services, about 1,000 to 2,000 a second.
- A rebuild took longer than the 15 minutes of the timer, and the timer started a second one. The two failed on the unique keys of Keycloak. Now only one rebuild runs for a realm at a time.
- A call that took more than 300 seconds did all its work and then answered with the status 500. The transaction of the request had ended by then, and its end failed. Now a long job ends that transaction before it starts.
- A sync with nothing to change was close to that limit of 300 seconds for 500,000 people, because it asked for the employments and the reporting line of each user. It now takes 33 seconds. See "The reads of a piece at once" above.

## The reads of a full rebuild

A full rebuild with nothing to change sent about one statement to the database for each person. Two reads did that.

- The users of the assignments. The rebuild read the employments of the assignments of a piece, and then each employment read its person with a select of its own. Now one query for each piece of 100 org units reads the user of each assignment through its employment and its person.
- The groups of the organization. The rebuild walked them through the API of Keycloak, which reads the subgroups of one group at a time. The realms cache of Keycloak holds groups, and it keeps 10,000 entries by default. So the walk of 5,000 groups came from the cache, but the walk of 50,000 groups went to the database for almost every group. Now one query reads every group of the organization with its attribute `fuhito.orgUnitId`.

The groups come in the order of the walk of Keycloak. When two groups name one unit, the first of them stays, so the order decides what a rebuild writes. Keycloak gives the groups at the top of an organization in the order of names in the database, and the subgroups of a group in the order of names in Java.

Measured on 2026-11-18 on a laptop with an Apple M4 Pro and 48 GB of memory. Docker (OrbStack 29.8.2) had 2 CPUs and 8 GB of memory for Keycloak and PostgreSQL together. Keycloak 26.8.2 and PostgreSQL 18.7.
Before is `main` at commit `637d17c`. After is commit `454bc8b`.

Each size was filled once with `BATCH=1 SYNC=0 scripts/bench/run.sh` and the jar of before. Then Keycloak started again with each jar on the same database. After one rebuild to warm up, five full rebuilds ran with nothing to change.
The time is the median of the five, with the fastest and the slowest. `pg_stat_statements` counted the statements of the database of Keycloak during each rebuild, and the number is their median.

| Realm | Before | After |
|---|---|---|
| 50,000 people, 5,000 org units, time | 3.0 s (2.8 to 3.2) | 0.54 s (0.52 to 0.59) |
| 50,000 people, 5,000 org units, statements | 50,436 | 385 |
| 500,000 people, 50,000 org units, time | 41.6 s (41.0 to 42.9) | 8.0 s (7.9 to 8.3) |
| 500,000 people, 50,000 org units, statements | 610,626 | 3,535 |

Every rebuild before and after reported no change.

- With 500,000 people, the select of one person ran 500,000 times. The walk of the groups read one group and its subgroups about 50,000 times each, and the attributes of the groups about 6,500 times.
- Of the 3,535 statements that are left with 500,000 people, about 1,000 start a transaction. The others are reads of a piece of 100 org units or of a page of 1,000 recorded members, about 500 of each kind.

## The fill and the clear of an attribute

After the reads, `scripts/bench/run.sh` defines a required attribute of people with `fillValue`, and deletes it with `clearValues=true`. Then it does the same for org units. It runs this three times. `FILL=0` leaves it out.
The fill of people writes the value into 50,000 rows, and the fill of org units into the 6,001 versions of 5,001 org units. Each clear removes it from as many rows.

Run on 2026-11-21 at commit `7b724d8`, with Keycloak 26.8.2 and PostgreSQL 18, on a laptop with an Apple M4 Pro and 48 GB of memory. Docker (OrbStack 29.8.2) had 2 CPUs and 8 GB of memory for Keycloak and PostgreSQL together.
Each number is the time of one request, measured by curl on the same machine.

| Write | Rows | Round 1 | Round 2 | Round 3 |
|---|---|---|---|---|
| Fill of people | 50,000 | 1,842 ms | 1,755 ms | 1,783 ms |
| Clear of people | 50,000 | 1,869 ms | 1,605 ms | 1,541 ms |
| Fill of org units | 6,001 | 614 ms | 580 ms | 573 ms |
| Clear of org units | 6,001 | 542 ms | 577 ms | 561 ms |

What the runs showed.

- The write holds the realm alone for the whole request. So the other writes of the realm wait up to about 2 seconds for a fill or a clear of 50,000 people, and about 0.6 seconds for one of 5,000 org units. That is short enough to keep the lock.
- For people, the statements took about 0.7 seconds in the database. The update of the attributes took about 300 ms, the count up of the versions about 250 ms, and the read of the IDs and the check of the size about 60 ms each. The rest of the time went into the 50,000 entries of the change log and the admin events, which Keycloak writes one by one.
- For org units, the update of the 6,001 versions took about 420 ms of the 0.6 seconds. The scan and the new JSON text took 11 ms of it. The database checks the exclusion constraint of the versions for each new row.
- The other values of a row keep their text, as the update reads the text of each value with `json_each` and writes it back as it was.

## The assignments that end on the start of another

A read of an assignment lists in `endedOnStart` the assignments of the same person that end on its start with the same primary flag. A list reads them for all its entries with one query. In the realm of the first table, three people in ten moved in 2024, so the assignment of each of them from 2024 lists the one before it.

`main` at commit `ebf98f1` and the change at commit `ad1f8b6` ran at the same time, each with its own Keycloak and PostgreSQL in Docker, filled by `scripts/bench/seed.sql`. Each request went to one and then to the other, so both saw the same load of the machine. Each number is the best of 30 requests, in ms.
Run on 2026-11-22 on the same laptop. Other jobs used the machine during the run, so both columns are a little higher than the first table.

| Read | Path | Before | After |
|---|---|---|---|
| One org unit | `/org-units/u-4321` | 3 | 4 |
| People, first page | `/people?max=100` | 3 | 3 |
| Assignments, first page | `/assignments?max=100` | 20 | 20 |
| Assignments, page of 1,000 | `/assignments?max=1000` | 150 | 157 |
| Members of a team | `/org-units/u-4321/members` | 4 | 5 |
| Members below the company, first page | `/org-units/u-0/members?includeDescendants=true&max=100` | 82 | 86 |
| Members below a division, 1,000 people | `/org-units/u-1/members?includeDescendants=true&max=1000` | 40 | 51 |
| Assignments of a person | `/people/p-12345/assignments?includePath=true` | 5 | 5 |
| History of a person | `/people/p-12345/history` | 4 | 5 |
| One assignment after a move | `/assignments/a-12342` | 2 | 3 |
| History of a person who moved | `/people/p-12342/history` | 5 | 5 |

What the runs showed.

- A list of 1,000 assignments costs 7 to 11 ms more. The query took about 10 ms of that in `EXPLAIN ANALYZE`. A page of 100 costs at most 4 ms more. A read of one assignment or of one person costs about 1 ms more.
- The query takes the last row of an assignment as the one with the latest start, which the unique key gives in one step. With `not exists` on a later row, PostgreSQL took the GiST index of the periods of the rows, and the query of 1,000 assignments took 14 ms.
- `IDX_FUHITO_ASSIGNMENT_EMPLOYMENT` got `VALID_TO` after the employment. In this realm an employment has 1.3 rows of assignments, so the plan reads all rows of the employment and checks the end after. With the statistics of 13 rows for each employment, the plan puts the end into the condition of the index.

## A new work location of an org unit

After the reads, `scripts/bench/run.sh` gives three org units another work location, with a body made from the read of the unit on the date of the change. `PLACES=0` leaves it out.
Each write counts up the version of each unit below and each assignment whose read changes, and records a propagated change of each. Such a write holds the realm alone. See "Propagated changes" in [keycloak-integration.md](keycloak-integration.md).
200 ms after each write starts, the script sends `POST /org-units` for a new unit. That write shares the realm, so it waits for a write that holds the realm alone.

`main` at commit `036b1ff2` and the change at commit `2237705a` ran one after the other, each with its own fill of the realm, on the same laptop as above. One request each, in ms.

| Write | Propagated changes | Before | After | New unit, before | New unit, after |
|---|---|---|---|---|---|
| A team with 10 members, from today | 9 | 58 | 99 | 14 | 53 |
| The company, from its first date | 47,373 | 38 | 6,478 | 10 | 6,280 |
| The company, from today | 36,439 | 35 | 4,235 | 10 | 4,052 |

What the runs showed.

- The realm is held alone for the whole write. So the other writes of the realm wait up to about 6.5 seconds for a new work location of the whole company, and about 0.1 seconds for one of a team. The team was done before the new unit came. An earlier run of the change gave 87, 5,459, and 3,620 ms.
- The change from the first date covers every period of every assignment with no work location of its own, also the periods that ended in 2024. The change from today covers the periods of today.
- The update of the versions of the assignments took 5.2 of the 6.5 seconds, and 3.4 of the 4.2 seconds. The version is on every row of an assignment, so each row gets a new row version. PostgreSQL then checks the two exclusion constraints of the assignments for each one. In a transaction that dropped the two constraints, the same update of 45,000 assignments took 0.5 seconds instead of 3.5. See #792.
- The walk down from the company over every date took about 8 ms for 5,001 units. The read of the 76,543 rows of assignments that take a value took about 7 ms, and the count up of 7,287 org units about 12 ms. Most of the rest is the 47,373 entries of the change log.
- A fillfactor of 80 on the table of assignments did not help. Most rows of a page get a new version at once, so few of them fit on the same page.

## What the numbers say

- A read about one org unit, one person, or one page of people does not grow with the realm.
- A read of the whole tree costs about 80 ms for 5,000 org units, also for the first page. The database walks the whole tree and orders it before it cuts the page. A subtree costs as much as its own size.
- The members below the whole company cost more on a late page. The database counts past the skipped rows. A reader that goes through all people pays that on every page.
- The effective changes scanned the tables of employments, terms, and assignments in the first run, because no index started with the realm and a date. The indexes above fixed that.
- The tree does not need another table design for 5,000 org units. A walk over them stays below 100 ms. For 50,000 org units see the section above.

## Not measured

- The token mapper.
- Writes other than the fill and the clear of an attribute and the new work locations above.
- An import that changes the work location of many stored units. Each such row reads what applies below its unit before and after the write.
- More than one request at the same time.

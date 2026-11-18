# Performance

This document has the numbers of a run with a large realm, and says how to repeat it.
The numbers show which reads grow with the size of a realm. They are not a promise, because they depend on the machine.

## How to measure

`scripts/bench/run.sh` starts PostgreSQL and Keycloak with the extension in Docker, fills one realm, and measures the reads.
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
- The number 333 ms for the whole tree with the two indexes does not hold in general. In a second run the planner still chose the GiST index of the exclusion constraint, and the read took 1.2 seconds. It chose the plain index only after the index was built again. Which one it takes depends on the sizes of the two indexes. The section below has the walk that does not depend on that choice.

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
- The third finds the latest version that started, and then reads it by its ID. Only plain indexes can serve these two lookups, so the choice is not left to the database. The cost follows the size of the subtree.

The extension uses the third. A subtree of a part of the organization is the usual read, and a read of the whole company is rare.

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

`BATCH=1 SYNC=0 scripts/bench/run.sh` also makes one Keycloak user for each person, links the company to one Keycloak organization, and runs a full rebuild twice with `POST /projection/rebuild?full=true`.
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

## A run of the changes of the projection

`POST /projection/rebuild` looks only at what the change log names since the last run, and `POST /projection/rebuild?full=true` runs a full rebuild. See "A run of the changes" in [keycloak-integration.md](keycloak-integration.md).
`scripts/bench/projection-changes.sh` measures it against a full rebuild at the same commit. It runs after `BATCH=1 SYNC=0 scripts/bench/run.sh`, which ends with two full rebuilds.

- `run.sh` turns the timer of the projection off, so only the measured calls rebuild.
- Before each run, the script makes the change of its case through the API. So the change log has the same entries as for any other caller. Each run gets a change of its own. A run of the changes and a full rebuild take turns.
- Each case runs five times with each kind of run. A cell has the median, and the fastest and the slowest run in brackets.
- `pg_stat_statements` counts the SQL statements of one whole run, also those of Keycloak.

| Case | The change before each run |
|---|---|
| Nothing changed | None |
| 10, 100, or 1,000 assignments moved | That many people in teams move to the next team, with one call of `POST /assignments/{id}/move` each. Each move makes two entries of the change log. |
| One unit renamed | A team gets a new name. |
| One subtree moved | A department moves to another division with the teams below it. A department has about nine. |

Run on 2026-11-18 at commit `395d510`, with Keycloak 26.8.2 and PostgreSQL 18, on a laptop with an Apple M4 Pro and 48 GB of memory, with macOS 26.7.
Docker is OrbStack 29.8.2 with 2 CPUs and 8 GB of memory for Keycloak and PostgreSQL together. Containers of other projects ran on the same Docker.

### 50,000 people and 5,000 org units

The time of one call.

| Case | Full rebuild | Run of the changes |
|---|---|---|
| Nothing changed | 3,328 ms (2,888 to 3,668) | 12 ms (11 to 20) |
| 10 assignments moved | 3,018 ms (2,723 to 3,168) | 143 ms (131 to 170) |
| 100 assignments moved | 3,255 ms (2,760 to 3,476) | 215 ms (153 to 223) |
| 1,000 assignments moved | 3,964 ms (3,163 to 4,522) | 645 ms (510 to 762) |
| One unit renamed | 3,172 ms (3,149 to 3,214) | 154 ms (139 to 157) |
| One subtree moved | 3,117 ms (3,112 to 3,211) | 137 ms (129 to 152) |

The SQL statements of one run, the median.

| Case | Full rebuild | Run of the changes |
|---|---|---|
| Nothing changed | 50,440 | 13 |
| 10 assignments moved | 50,546 | 169 |
| 100 assignments moved | 51,555 | 1,255 |
| 1,000 assignments moved | 61,546 | 11,616 |
| One unit renamed | 50,461 | 60 |
| One subtree moved | 50,470 | 65 |

### 500,000 people and 50,000 org units

`PEOPLE=500000 UNITS=50000 BATCH=1 SYNC=0 scripts/bench/run.sh`, then `UNITS=50000 CASES="nothing 10" scripts/bench/projection-changes.sh`.
The seed, the users, and the first full rebuild took 29 minutes together. Other tests shared the Docker for part of that time, but not during the runs below.

The time of one call.

| Case | Full rebuild | Run of the changes |
|---|---|---|
| Nothing changed | 43,110 ms (42,799 to 43,381) | 21 ms (11 to 32) |
| 10 assignments moved | 42,369 ms (41,976 to 42,574) | 1,420 ms (1,392 to 1,526) |

The SQL statements of one run, the median.

| Case | Full rebuild | Run of the changes |
|---|---|---|
| Nothing changed | 602,830 | 36 |
| 10 assignments moved | 605,008 | 181 |

The next commit, `a846883`, changes only the order of the writes for a user who joins another organization. No case here does that.
At the measured commit, `changesOnly=true` asked for a run of the changes and a full rebuild was the default. Later the run of the changes became the default. The runs themselves are the same.

What the runs showed.

- With nothing to change, a run of the changes reads its checkpoint and one page of the change log.
- For moved assignments, a run of the changes reads and writes for each user that the change log names. That is about 12 statements for each user, and most of them are the writes through the API of Keycloak. A full rebuild makes the same writes.
- With nothing to change, a full rebuild runs about 50,000 statements. 50,000 of them read the person of one employment each. The other reads come in pieces of 100 org units, about 50 queries each.
- A renamed unit or a moved subtree changes only the groups of that unit and below it. The run still reads the whole tree of the date and every group of the realm that mirrors a unit. It reads them once for the check of the permissions and once more in the run.
- With nothing to change, a run of the changes does not grow with the realm. The 23 more statements at 500,000 people are Keycloak, which loads roles and clients into its cache for the request.
- With moved assignments, a run of the changes grows with the realm. At 500,000 people, the reads of every org unit, every version and parent link of today, and every group that mirrors a unit return about 500,000 rows. The database spends about 160 ms on them, and loading them takes most of the 1.4 seconds. A walk up the tree in the database from the units of the users, and a read of only their groups, would not grow with the realm.
- A full rebuild grows with the realm in the same way as before. 500,000 of its statements read the person of one employment each.

## What the numbers say

- A read about one org unit, one person, or one page of people does not grow with the realm.
- A read of the whole tree costs about 80 ms for 5,000 org units, also for the first page. The database walks the whole tree and orders it before it cuts the page. A subtree costs as much as its own size.
- The members below the whole company cost more on a late page. The database counts past the skipped rows. A reader that goes through all people pays that on every page.
- The effective changes scanned the tables of employments, terms, and assignments in the first run, because no index started with the realm and a date. The indexes above fixed that.
- The tree does not need another table design for 5,000 org units. A walk over them stays below 100 ms. For 50,000 org units see the section above.

## Not measured

- The token mapper.
- Writes.
- More than one request at the same time.

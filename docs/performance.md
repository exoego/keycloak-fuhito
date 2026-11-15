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
So the versions got a plain index on the org unit and `VALID_FROM`, and the parent links got one on the hierarchy, the child, and `VALID_FROM`. The second one is also the only index from a child to its parent in a database without `btree_gist`.

What is still slow at this size, and why.

- The whole tree costs about 330 ms for each page. The database walks all 50,000 org units and orders them before it cuts the page.
- The members below the whole company cost about 600 ms for the first page, and 3 seconds for a late page. The database orders all 500,000 assignments by the place of their org unit in the tree, and then counts past the skipped rows.

A reader of a late page pays for all pages before it. A cursor that says "go on after this row" would make every page cost as much as the first one.

## What changed after these runs

- The read of the whole tree, `GET /org-units` with every org unit, is gone. `GET /org-units` now lists the roots. The rows "Org units, first page" and "Org units, last page" above are from before that, and the script no longer measures them. A subtree of a root still walks the whole tree.
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

## What the numbers say

- A read about one org unit, one person, or one page of people does not grow with the realm.
- A read of the whole tree costs about 80 ms for 5,000 org units, also for the first page. The database walks the whole tree and orders it before it cuts the page. A subtree costs as much as its own size.
- The members below the whole company cost more on a late page. The database counts past the skipped rows. A reader that goes through all people pays that on every page.
- The effective changes scanned the tables of employments, terms, and assignments in the first run, because no index started with the realm and a date. The indexes above fixed that.
- The tree does not need another table design for 5,000 org units. A walk over them stays below 100 ms. For 50,000 org units see the section above.

## Not measured

- The token mapper, the projection into Keycloak, and the sync from user attributes. They need Keycloak users and organizations, which the seed does not make.
- Writes.
- More than one request at the same time.

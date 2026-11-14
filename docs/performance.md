# Performance

This document has the numbers of a run with a large realm, and says how to repeat it.
The numbers show which reads grow with the size of a realm. They are not a promise, because they depend on the machine.

## How to measure

`scripts/bench/run.sh` starts PostgreSQL and Keycloak with the extension in Docker, fills one realm, and measures the reads.
It needs Docker, curl, and jq. Run it from the root of the project. It is not a check of CI.

`scripts/bench/seed.sql` fills the realm with SQL, because the API would take too long for this many rows.

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

## What the numbers say

- A read about one org unit, one person, or one page of people does not grow with the realm.
- A read of the whole tree costs about 80 ms for 5,000 org units, also for the first page. The database walks the whole tree and orders it before it cuts the page. A subtree costs as much as its own size.
- The members below the whole company cost more on a late page. The database counts past the skipped rows. A reader that goes through all people pays that on every page.
- The effective changes scanned the tables of employments, terms, and assignments in the first run, because no index started with the realm and a date. The indexes above fixed that.
- The tree does not need another table design at this size. A walk over 5,000 org units stays below 100 ms.

## Not measured

- The token mapper, the projection into Keycloak, and the sync from user attributes. They need Keycloak users and organizations, which the seed does not make.
- Writes.
- More than one request at the same time.

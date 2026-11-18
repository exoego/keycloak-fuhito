#!/usr/bin/env bash
# Measures a run of the changes of the projection against a full rebuild, at the same commit.
# Start the realm first with "BATCH=1 SYNC=0 scripts/bench/run.sh". That leaves Keycloak and PostgreSQL running
# after two full rebuilds. This script makes changes through the API, and measures each case RUNS times with each
# kind of run. Each run gets a change of its own.
# It needs Docker, curl, and jq. Run it from the root of the project. It is not a check of CI.
set -euo pipefail

PORT="${PORT:-18089}"
BASE="http://127.0.0.1:${PORT}"
REALM=bench
API="${BASE}/admin/realms/${REALM}/fuhito"
# The size of the seed. It must be the one that run.sh used.
UNITS="${UNITS:-5000}"
RUNS="${RUNS:-5}"
# The cases: nothing, a number of assignments that move to another unit, rename, and subtree.
CASES="${CASES:-nothing 10 100 1000 rename subtree}"
# One line for each run: the case, the kind of run, the number of the run, ms, the status, statements, the report.
RAW="${RAW:-build/bench/projection-changes.tsv}"
TODAY="$(date -u +%F)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

token() {
  curl -s -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
    "${BASE}/realms/master/protocol/openid-connect/token" | jq -r .access_token
}

sql() {
  docker exec -i fuhito-bench-pg psql -q -t -A -U keycloak -d keycloak -v ON_ERROR_STOP=1 -c "$1"
}

sql "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
mkdir -p "$(dirname "${RAW}")"
: >"${RAW}"
REALM_ID="$(sql "SELECT id FROM realm WHERE name = '${REALM}'")"

# Calls the API with a body and fails unless the status is the expected one.
call() {
  local method="$1" path="$2" body="$3" expected="$4" status
  status="$(curl -s -o "${WORK}/call" -w '%{http_code}' -X "${method}" -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' -d "${body}" "${API}${path}")"
  if [ "${status}" != "${expected}" ]; then
    echo "${method} ${path} answered ${status}: $(head -c 300 "${WORK}/call")" >&2
    exit 1
  fi
}

# Moves assignments of people in teams to the next team. An assignment that moved today cannot move again today,
# so each call picks others.
move() {
  local count="$1" done=0 id version unit
  TOKEN="$(token)"
  while IFS='|' read -r id version unit; do
    # A token lasts a minute.
    if [ $((done % 200)) -eq 199 ]; then TOKEN="$(token)"; fi
    call POST "/assignments/${id}/move" \
      "{\"version\": ${version}, \"orgUnitId\": \"${unit}\", \"validFrom\": \"${TODAY}\"}" 201
    done=$((done + 1))
  done < <(sql "SELECT assignment_id, version,
      'u-' || (551 + (substr(org_unit_id, 3)::int - 550) % (${UNITS} - 550))
    FROM fuhito_assignment WHERE realm_id = '${REALM_ID}' AND valid_to IS NULL AND valid_from < DATE '${TODAY}'
    AND substr(org_unit_id, 3)::int > 550 ORDER BY assignment_id LIMIT ${count}")
}

version() {
  curl -s -H "Authorization: Bearer ${TOKEN}" "${API}/org-units/$1/history" | jq -r .version
}

# Renames the team with the given number.
rename() {
  TOKEN="$(token)"
  call PUT "/org-units/u-$1" "{\"version\": $(version "u-$1"), \"name\": \"Renamed team $1\", \"validFrom\": \"${TODAY}\"}" 200
}

# Moves the department with the given number, with the teams below it, to the next division.
subtree() {
  local division=$((1 + ($1 - 51) / 10))
  TOKEN="$(token)"
  call PUT "/org-units/u-$1/parent" \
    "{\"version\": $(version "u-$1"), \"parentId\": \"u-$((division % 50 + 1))\", \"validFrom\": \"${TODAY}\"}" 200
}

# Each change takes a unit of its own.
NEXT=0
change() {
  NEXT=$((NEXT + 1))
  case "$1" in
    nothing) ;;
    rename) rename $((600 + NEXT)) ;;
    subtree) subtree $((51 + NEXT)) ;;
    *) move "$1" ;;
  esac
}

# Runs one rebuild and appends its time, its status, the number of SQL statements, and its report to RAW.
measure() {
  local name="$1" kind="$2" run="$3" query="$4" line statements
  TOKEN="$(token)"
  sql "SELECT pg_stat_statements_reset()" >/dev/null
  line="$(curl -s -o "${WORK}/report" -w '%{time_total} %{http_code}' --max-time 3600 -X POST \
    -H "Authorization: Bearer ${TOKEN}" "${API}/projection/rebuild${query}")"
  statements="$(sql "SELECT sum(s.calls) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
    WHERE d.datname = 'keycloak'")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${name}" "${kind}" "${run}" \
    "$(awk '{printf "%d", $1 * 1000}' <<<"${line}")" "$(awk '{print $2}' <<<"${line}")" "${statements}" \
    "$(tr -d '\n' <"${WORK}/report")" >>"${RAW}"
  tail -1 "${RAW}" | cut -c1-200
}

for name in ${CASES}; do
  for run in $(seq 1 "${RUNS}"); do
    change "${name}"
    measure "${name}" changes "${run}" '?changesOnly=true'
    change "${name}"
    measure "${name}" full "${run}" ''
  done
done

median() {
  awk '{ v[NR] = $1 } END { print v[int((NR + 1) / 2)] }' <<<"$1"
}

# The median of the runs of one case and kind, with the fastest and the slowest, and the median of statements.
summary() {
  local rows ms statements
  rows="$(awk -F '\t' -v name="$1" -v kind="$2" '$1 == name && $2 == kind' "${RAW}")"
  ms="$(cut -f4 <<<"${rows}" | sort -n)"
  statements="$(cut -f6 <<<"${rows}" | sort -n)"
  printf '%s ms (%s to %s), %s statements' "$(median "${ms}")" "$(head -1 <<<"${ms}")" "$(tail -1 <<<"${ms}")" \
    "$(median "${statements}")"
}

echo
echo '| Case | Full rebuild | Run of the changes |'
echo '|---|---|---|'
for name in ${CASES}; do
  echo "| ${name} | $(summary "${name}" full) | $(summary "${name}" changes) |"
done

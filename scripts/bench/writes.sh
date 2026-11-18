#!/usr/bin/env bash
# Fills a realm like run.sh and measures writes, one after another and several at once.
# It needs Docker, curl, jq, and perl. Run it from the root of the project. It is not a check of CI.
set -euo pipefail

KEYCLOAK_VERSION="${KEYCLOAK_VERSION:-26.8.2}"
PORT="${PORT:-18089}"
BASE="http://127.0.0.1:${PORT}"
REALM=bench
# The size of the realm, the same as in run.sh.
PEOPLE="${PEOPLE:-50000}"
UNITS="${UNITS:-5000}"
# Each kind of write runs WARMUP times, and then RUNS times REQUESTS times.
WARMUP="${WARMUP:-100}"
REQUESTS="${REQUESTS:-300}"
RUNS="${RUNS:-3}"
WRITERS="${WRITERS:-8}"
# The count of SQL statements takes this many writes of each kind.
COUNTED="${COUNTED:-50}"

# JAR can name a jar that is built already, for example one of another branch.
if [ -z "${JAR:-}" ]; then
  ./gradlew jar -q
  JAR="${PWD}/$(ls build/libs/keycloak-fuhito-*-SNAPSHOT.jar | head -1)"
fi

docker rm -f fuhito-bench-kc fuhito-bench-pg >/dev/null 2>&1 || true
docker network create fuhito-bench >/dev/null 2>&1 || true
docker run -d --name fuhito-bench-pg --network fuhito-bench \
  -e POSTGRES_DB=keycloak -e POSTGRES_USER=keycloak -e POSTGRES_PASSWORD=keycloak postgres:18 \
  -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_utility=off >/dev/null
docker run -d --name fuhito-bench-kc --network fuhito-bench -p "127.0.0.1:${PORT}:8080" \
  -v "${JAR}:/opt/keycloak/providers/fuhito.jar:ro" \
  -e KC_DB=postgres -e KC_DB_URL=jdbc:postgresql://fuhito-bench-pg:5432/keycloak \
  -e KC_DB_USERNAME=keycloak -e KC_DB_PASSWORD=keycloak \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  "quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}" start-dev >/dev/null
curl -s -o /dev/null --retry 120 --retry-delay 2 --retry-all-errors --max-time 5 "${BASE}/realms/master"

token() {
  curl -s -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
    "${BASE}/realms/master/protocol/openid-connect/token" | jq -r .access_token
}

# One token lasts for the whole run.
curl -s -o /dev/null -X PUT -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
  -d '{"accessTokenLifespan": 36000, "ssoSessionIdleTimeout": 36000, "ssoSessionMaxLifespan": 36000}' \
  "${BASE}/admin/realms/master"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
  -d "{\"realm\": \"${REALM}\", \"enabled\": true, \"organizationsEnabled\": true, \"attributes\": {\"fuhito.timeZone\": \"UTC\", \"fuhito.projection.enabled\": \"true\", \"fuhito.sync.enabled\": \"true\"}}" \
  "${BASE}/admin/realms"
REALM_ID="$(curl -s -H "Authorization: Bearer $(token)" "${BASE}/admin/realms/${REALM}" | jq -r .id)"
sql() {
  docker exec -i fuhito-bench-pg psql -q -U keycloak -d keycloak -v ON_ERROR_STOP=1 "$@"
}
sql -v realm="${REALM_ID}" -v people="${PEOPLE}" -v units="${UNITS}" < scripts/bench/seed.sql
# 400 occupations, and 400 assignments with 20 periods each, one period every three months.
sql -v realm="${REALM_ID}" -v units="${UNITS}" <<'SQL'
INSERT INTO fuhito_ref_item (id, realm_id, kind, code, name, names_i18n, display_order, attributes, active, legal_entity)
SELECT 'bench-occ-' || n, :'realm', 'OCCUPATION', 'occ-' || n, 'Occupation ' || n, '{}', 0, '{}', true, false
FROM generate_series(1, 400) n;
INSERT INTO fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id, valid_from, valid_to, occupation_id, is_head, is_primary, display_order, attributes)
SELECT 'm-' || n || '-' || k, :'realm', 'm-' || n, 'e-' || n, 'u-' || ((n + :units / 2) % (:units + 1)),
       (DATE '2020-04-01' + k * INTERVAL '3 months')::date,
       CASE WHEN k < 19 THEN (DATE '2020-04-01' + (k + 1) * INTERVAL '3 months')::date END,
       'bench-nurse', false, false, k, '{}'
FROM generate_series(1, 400) n, generate_series(0, 19) k;
ANALYZE;
CREATE EXTENSION pg_stat_statements;
SQL

API="${BASE}/admin/realms/${REALM}/fuhito"
TOKEN="$(token)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

version() {
  local found
  found="$(curl -s -H "Authorization: Bearer ${TOKEN}" "${API}$1" | jq -r .version)"
  if [ -z "${found}" ] || [ "${found}" = null ]; then
    echo "No version in ${API}$1" >&2
    exit 1
  fi
  echo "${found}"
}

# Each kind of write reads the resource of a number like a client. It prints the method, the path, and the body
# with the version that it read. A write that adds a period gets a resource of its own for each number.
ref_item() {
  local id="bench-occ-$(($1 % 400 + 1))"
  printf 'PUT /ref-items/%s {"version": %s, "name": "Occupation %s", "displayOrder": %s, "active": true}\n' \
    "${id}" "$(version "/ref-items/${id}")" "$1" "$1"
}
org_unit() {
  local n=$((UNITS / 5 + $1))
  printf 'PUT /org-units/u-%s {"version": %s, "validFrom": "2030-01-01", "code": "c%s", "name": "Unit %s renamed", "unitType": "unit"}\n' \
    "${n}" "$(version "/org-units/u-${n}")" "${n}" "${n}"
}
assignment_details() {
  local id="m-$(($1 % 400 + 1))"
  printf 'PUT /assignments/%s/details {"version": %s, "validFrom": "2025-01-01", "occupation": "nurse", "primary": false, "displayOrder": %s}\n' \
    "${id}" "$(version "/assignments/${id}")" "$1"
}
terms_of() {
  printf 'PUT /employments/e-%s/terms {"version": %s, "validFrom": "2030-01-01", "employmentType": "full-time", "status": "ACTIVE", "workPercent": 80}\n' \
    "$1" "$(version "/employments/e-$1")"
}
terms() {
  terms_of "$((PEOPLE / 4 + $1))"
}
move() {
  local n=$((PEOPLE / 2 + $1))
  printf 'POST /assignments/a-%s/move {"version": %s, "orgUnitId": "u-%s", "validFrom": "2030-01-01"}\n' \
    "${n}" "$(version "/assignments/a-${n}")" "$(((n % (UNITS + 1) + 1) % (UNITS + 1)))"
}

# Reads the resources of the numbers from $2 to $3 with the function $1, like a client. It writes a config file of
# curl with one write for each.
prepare() {
  local write="$1" i request rest
  : >"${WORK}/config"
  for ((i = $2; i <= $3; i++)); do
    request="$("${write}" "${i}")"
    rest="${request#* }"
    if [ "${i}" -gt "$2" ]; then echo next >>"${WORK}/config"; fi
    printf 'url = "%s"\nrequest = "%s"\noutput = "/dev/null"\nwrite-out = "%%{time_total} %%{http_code} %%{url}\\n"\nheader = "Authorization: Bearer %s"\nheader = "Content-Type: application/json"\ndata = "%s"\n' \
      "${API}${rest%% *}" "${request%% *}" "${TOKEN}" "$(sed 's/"/\\"/g' <<<"{${rest#* \{}")" >>"${WORK}/config"
  done
}

# Sends the writes of the config file one after another on one connection, or $1 at once. It keeps the time of each
# write in ms. A write that fails stops the run.
send() {
  if [ -n "${1:-}" ]; then
    curl -s -Z --parallel-max "$1" --parallel-immediate -K "${WORK}/config" >"${WORK}/lines"
  else
    curl -s -K "${WORK}/config" >"${WORK}/lines"
  fi
  if awk '$2 !~ /^2/ {bad = 1} END {exit !bad}' "${WORK}/lines"; then
    echo "A write failed: $(awk '$2 !~ /^2/' "${WORK}/lines" | head -3)" >&2
    exit 1
  fi
  awk '{printf "%.3f\n", $1 * 1000}' "${WORK}/lines" >"${WORK}/times"
}

# Prints the median and the 95th percentile of the times, by nearest rank.
stats() {
  sort -n "${WORK}/times" | awk '{t[NR] = $1} END {m = NR * 0.5; p = NR * 0.95;
    m = int(m) + (m > int(m)); p = int(p) + (p > int(p)); printf "%.1f %.1f", t[m], t[p]}'
}

# Prints the median and the 95th percentile of the run with the lowest median.
sequential() {
  local write="$1" from=$((WARMUP + 1)) run best="" line
  prepare "${write}" 1 "${WARMUP}"
  send
  for ((run = 1; run <= RUNS; run++)); do
    prepare "${write}" "${from}" "$((from + REQUESTS - 1))"
    send
    from=$((from + REQUESTS))
    line="$(stats)"
    echo "  ${write}, run ${run}: median and p95 ${line}" >&2
    if [ -z "${best}" ] || awk -v a="${line%% *}" -v b="${best%% *}" 'BEGIN {exit !(a < b)}'; then best="${line}"; fi
  done
  echo "${best}"
}

# Prints the SQL statements of each write and the rows that they wrote.
statements() {
  local from=$((WARMUP + RUNS * REQUESTS + 1))
  prepare "$1" "${from}" "$((from + COUNTED - 1))"
  sql -c 'SELECT pg_stat_statements_reset()' >/dev/null
  send
  sql -At -F ' ' -v counted="${COUNTED}" <<'SQL'
SELECT round(sum(calls) / :counted, 1),
       round(coalesce(sum(calls) FILTER (WHERE kind IN ('select', 'with')), 0) / :counted, 1),
       round(coalesce(sum(calls) FILTER (WHERE kind = 'insert'), 0) / :counted, 1),
       round(coalesce(sum(calls) FILTER (WHERE kind = 'update'), 0) / :counted, 1),
       round(coalesce(sum(calls) FILTER (WHERE kind = 'delete'), 0) / :counted, 1),
       round(coalesce(sum(rows) FILTER (WHERE kind IN ('insert', 'update', 'delete')), 0) / :counted, 1)
FROM (SELECT lower(substring(ltrim(query) FROM '^[A-Za-z]+')) AS kind, calls, rows FROM pg_stat_statements
      WHERE query NOT LIKE '%pg_stat_statements%') s
SQL
}

# The writers write the terms of different employments at once.
parallel() {
  local from=$((PEOPLE * 3 / 4 + 1)) run n best="" line start end
  for ((run = 0; run <= RUNS; run++)); do
    n=$((run == 0 ? WARMUP : REQUESTS))
    prepare terms_of "${from}" "$((from + n - 1))"
    from=$((from + n))
    start="$(perl -MTime::HiRes=time -e 'printf "%.6f", time')"
    send "${WRITERS}"
    end="$(perl -MTime::HiRes=time -e 'printf "%.6f", time')"
    if [ "${run}" -eq 0 ]; then continue; fi
    line="$(stats) $(awk -v n="${n}" -v s="${start}" -v e="${end}" 'BEGIN {printf "%d", n / (e - s)}')"
    echo "  parallel, run ${run}: median, p95, and writes per second ${line}" >&2
    if [ -z "${best}" ] || [ "${line##* }" -gt "${best##* }" ]; then best="${line}"; fi
  done
  echo "${best}"
}

row() {
  local times counts
  times="$(sequential "$2")"
  counts="$(statements "$2")"
  printf '| %s | %s | %s |\n' "$1" "${times// / | }" "${counts// / | }"
}

echo '| Write | Median ms | p95 ms | Statements | Select | Insert | Update | Delete | Rows written |'
echo '|---|---|---|---|---|---|---|---|---|'
row 'Ref item' ref_item
row 'Org unit, new period' org_unit
row 'Assignment with 20 periods, details' assignment_details
row 'Terms of an employment, new period' terms
row 'Move of an assignment' move

echo
echo "| ${WRITERS} writers at once, terms of different employments | Median ms | p95 ms | Writes per second |"
echo '|---|---|---|---|'
best="$(parallel)"
printf '| Best of %s runs of %s writes | %s |\n' "${RUNS}" "${REQUESTS}" "${best// / | }"

echo
echo 'Remove the containers with: docker rm -f fuhito-bench-kc fuhito-bench-pg && docker network rm fuhito-bench'

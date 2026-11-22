#!/usr/bin/env bash
# Fills a realm with a large organization and measures the reads.
# It needs Docker, curl, and jq. Run it from the root of the project. It is not a check of CI.
set -euo pipefail

KEYCLOAK_VERSION="${KEYCLOAK_VERSION:-26.8.2}"
PORT="${PORT:-18089}"
BASE="http://127.0.0.1:${PORT}"
REALM=bench
# The size of the realm. The defaults are the size of the first run in docs/performance.md.
PEOPLE="${PEOPLE:-50000}"
UNITS="${UNITS:-5000}"
# BATCH=1 also measures the projection into Keycloak and the sync from user attributes. They take minutes.
# SYNC=0 leaves the sync out.
BATCH="${BATCH:-0}"
# FILL=0 leaves out the writes of definitions that fill and clear an attribute in every row. They come after the
# reads, so the reads do not see the values.
FILL="${FILL:-1}"

# A caller that built the jar already can name it in JAR, as a path from the root of the project.
if [ -z "${JAR:-}" ]; then
  ./gradlew jar -q
  JAR="$(ls build/libs/keycloak-fuhito-*-SNAPSHOT.jar | head -1)"
fi

docker rm -f fuhito-bench-kc fuhito-bench-pg >/dev/null 2>&1 || true
docker network create fuhito-bench >/dev/null 2>&1 || true
# The database writes the plan of every statement that takes 20 ms or more to its log.
# pg_stat_statements counts the statements of a run.
docker run -d --name fuhito-bench-pg --network fuhito-bench \
  -e POSTGRES_DB=keycloak -e POSTGRES_USER=keycloak -e POSTGRES_PASSWORD=keycloak postgres:18 \
  -c shared_preload_libraries=auto_explain,pg_stat_statements -c auto_explain.log_min_duration=20ms \
  -c auto_explain.log_analyze=on -c auto_explain.log_nested_statements=on >/dev/null
# The timer of the projection is off, so only the measured calls rebuild it.
docker run -d --name fuhito-bench-kc --network fuhito-bench -p "127.0.0.1:${PORT}:8080" \
  -v "${PWD}/${JAR}:/opt/keycloak/providers/fuhito.jar:ro" \
  -e KC_DB=postgres -e KC_DB_URL=jdbc:postgresql://fuhito-bench-pg:5432/keycloak \
  -e KC_DB_USERNAME=keycloak -e KC_DB_PASSWORD=keycloak \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  "quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}" start-dev \
  --spi-admin-realm-restapi-extension--fuhito--projection-timer-enabled=false >/dev/null
curl -s -o /dev/null --retry 120 --retry-delay 2 --retry-all-errors --max-time 5 "${BASE}/realms/master"

token() {
  curl -s -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
    "${BASE}/realms/master/protocol/openid-connect/token" | jq -r .access_token
}

curl -s -o /dev/null -X POST -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
  -d "{\"realm\": \"${REALM}\", \"enabled\": true, \"organizationsEnabled\": true, \"attributes\": {\"fuhito.timeZone\": \"UTC\", \"fuhito.projection.enabled\": \"true\", \"fuhito.sync.enabled\": \"true\"}}" \
  "${BASE}/admin/realms"
REALM_ID="$(curl -s -H "Authorization: Bearer $(token)" "${BASE}/admin/realms/${REALM}" | jq -r .id)"
docker exec -i fuhito-bench-pg psql -q -U keycloak -d keycloak -v ON_ERROR_STOP=1 -v realm="${REALM_ID}" \
  -v people="${PEOPLE}" -v units="${UNITS}" \
  < scripts/bench/seed.sql

API="${BASE}/admin/realms/${REALM}/fuhito"
TOKEN="$(token)"

# Prints the best of five times in milliseconds, the size of the body, and the status.
measure() {
  local name="$1" path="$2" best=999999 line
  for _ in 1 2 3 4 5; do
    line="$(curl -s -o /dev/null -w '%{time_total} %{size_download} %{http_code}' \
      -H "Authorization: Bearer ${TOKEN}" "${API}${path}")"
    local ms; ms="$(awk '{printf "%d", $1 * 1000}' <<<"${line}")"
    if [ "${ms}" -lt "${best}" ]; then best="${ms}"; fi
  done
  printf '| %s | `%s` | %s | %s | %s |\n' "${name}" "${path}" "${best}" \
    "$(awk '{print $2}' <<<"${line}")" "$(awk '{print $3}' <<<"${line}")"
}

echo '| Read | Path | Best of 5 in ms | Bytes | Status |'
echo '|---|---|---|---|---|'
measure 'Every org unit, first page' '/org-units?max=100'
measure 'Every org unit, last page' "/org-units?first=$((UNITS - 100))&max=100"
measure 'Roots' '/org-units?roots=true'
measure 'Legal entities' '/org-units?legalEntity=true'
measure 'One org unit' '/org-units/u-4321'
measure 'Children of a division' '/org-units/u-1/children'
measure 'Subtree of the company, first page' '/org-units/u-0/subtree?max=100'
measure 'Subtree of a division' '/org-units/u-1/subtree?max=1000'
measure 'Path of a team' '/org-units/u-4321/path'
measure 'Members of a team' '/org-units/u-4321/members'
measure 'Members below the company, first page' '/org-units/u-0/members?includeDescendants=true&max=100'
measure 'Members below the company, deep page' "/org-units/u-0/members?includeDescendants=true&first=$((PEOPLE * 8 / 10))&max=100"
measure 'Members below a division' '/org-units/u-1/members?includeDescendants=true&max=1000'
measure 'Members of a team, by work location' '/org-units/u-4321/members?workLocation=site-0'
measure 'Members below the company, by work location' '/org-units/u-0/members?includeDescendants=true&max=100&workLocation=site-0'
measure 'Members below a division, by cost center' '/org-units/u-1/members?includeDescendants=true&max=1000&costCenter=cc-1'
measure 'People, first page' '/people?max=100'
measure 'People, deep page' "/people?first=$((PEOPLE - 1000))&max=100"
measure 'Assignments, first page' '/assignments?max=100'
measure 'Assignments, page of 1,000' '/assignments?max=1000'
measure 'Assignments of a person' '/people/p-12345/assignments?includePath=true'
measure 'History of a person' '/people/p-12345/history'
# Person n moved in 2024 when n % 10 < 3.
measure 'One assignment after a move' '/assignments/a-12342'
measure 'History of a person who moved' '/people/p-12342/history'
measure 'Managers of a person' '/people/p-12345/managers'
measure 'Reports of the head of the company' '/people/p-0/reports?max=100'
measure 'Reports of the head of a division' '/people/p-1/reports?max=1000'
measure 'Reports of the head of a team' '/people/p-4321/reports'
measure 'Change log, from the start' '/change-log?max=1000'
measure 'Change log, from the middle' "/change-log?after=$((PEOPLE * 2))&max=1000"
measure 'Change log of one resource' '/change-log?resourceType=FUHITO_ASSIGNMENT&resourceId=a-777'
measure 'Effective changes of one day' '/effective-changes?from=2024-04-01'
measure 'Effective changes of five years' '/effective-changes?from=2020-01-01&to=2026-01-01&max=1000'

if [ "${BATCH}" = 1 ]; then
  docker exec -i fuhito-bench-pg psql -q -U keycloak -d keycloak -v ON_ERROR_STOP=1 -v realm="${REALM_ID}" \
    -v people="${PEOPLE}" -v units="${UNITS}" < scripts/bench/seed-users.sql
  # The whole company is one Keycloak organization. Its org units become groups, and its people members.
  ORGANIZATION="$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $(token)" \
    -H 'Content-Type: application/json' \
    -d '{"name": "bench", "alias": "bench", "domains": [{"name": "bench.example.com"}]}' \
    "${BASE}/admin/realms/${REALM}/organizations" | tr -d '\r' | awk -F/ '/^[Ll]ocation:/ {print $NF}')"
  # A write of a stored unit names the version that the caller read.
  VERSION="$(curl -s -H "Authorization: Bearer $(token)" "${API}/org-units/u-0/history" | jq -r .version)"
  curl -s -o /dev/null -X PUT -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
    -d "{\"version\": ${VERSION}, \"organizationId\": \"${ORGANIZATION}\"}" "${API}/org-units/u-0/organization"

  # One run each. A token lasts a minute, so each call gets a new one.
  once() {
    local name="$1" path="$2" line
    line="$(curl -s -o /tmp/fuhito-bench-body -w '%{time_total} %{http_code}' --max-time 3600 -X POST \
      -H "Authorization: Bearer $(token)" "${API}${path}")"
    printf '| %s | `POST %s` | %s | %s | %s |\n' "${name}" "${path}" \
      "$(awk '{printf "%d", $1 * 1000}' <<<"${line}")" "$(awk '{print $2}' <<<"${line}")" \
      "$(head -c 300 /tmp/fuhito-bench-body)"
  }
  echo
  echo '| Batch | Path | ms | Status | Report |'
  echo '|---|---|---|---|---|'
  once 'Projection, first run' '/projection/rebuild'
  once 'Projection, nothing changed' '/projection/rebuild'
  if [ "${SYNC:-1}" = 1 ]; then
    once 'Sync from user attributes, first run' '/attribute-sync/run'
    once 'Sync from user attributes, nothing changed' '/attribute-sync/run'
  fi
fi

if [ "${FILL}" = 1 ]; then
  # Prints the time of one write in milliseconds, with its status, and keeps its body.
  write() {
    local name="$1" rows="$2" method="$3" path="$4" body="$5" line
    line="$(curl -s -o /tmp/fuhito-bench-body -w '%{time_total} %{http_code}' --max-time 3600 -X "${method}" \
      -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' ${body:+-d "${body}"} \
      "${API}${path}")"
    printf '| %s | %s | %s | %s |\n' "${name}" "${rows}" "$(awk '{printf "%d", $1 * 1000}' <<<"${line}")" \
      "$(awk '{print $2}' <<<"${line}")"
  }
  echo
  echo '| Write of a definition | Rows | ms | Status |'
  echo '|---|---|---|---|'
  # Each round defines a required attribute, which fills every row, and deletes it, which clears every row.
  for round in 1 2 3; do
    for target in PERSON ORG_UNIT; do
      if [ "${target}" = PERSON ]; then rows="${PEOPLE}"; else rows="$((UNITS + 1 + (UNITS + 1) / 5))"; fi
      write "Fill of ${target}, round ${round}" "${rows}" POST /attribute-definitions \
        "{\"target\": \"${target}\", \"name\": \"bench\", \"type\": \"STRING\", \"required\": true, \"fillValue\": \"x\"}"
      DEFINITION="$(jq -r .id /tmp/fuhito-bench-body)"
      VERSION="$(jq -r .version /tmp/fuhito-bench-body)"
      write "Clear of ${target}, round ${round}" "${rows}" DELETE \
        "/attribute-definitions/${DEFINITION}?version=${VERSION}&clearValues=true" ""
    done
  done
fi

echo
echo 'The plans of the slow statements are in the log of the database:'
echo '  docker logs fuhito-bench-pg 2>&1 | grep -A40 "duration:"'
echo 'Remove the containers with: docker rm -f fuhito-bench-kc fuhito-bench-pg'

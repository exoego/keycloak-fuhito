package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nurse whose person gets another Keycloak user. The employment, the assignment, the qualification, and the role
 * of the nurse read the user, and so does the line of a clerk who reports to the nurse. Nobody writes them.
 */
class LinkedUserChangesIT {

    private static final Keycloak ADMIN = KEYCLOAK.getKeycloakAdminClient();

    // The records of the nurse by their path, with the field of the user in their read.
    private record Nurse(TestRealm realm, String person, String user, Map<String, String> records) {

        Api api() {
            return realm.api;
        }
    }

    private static Nurse nurse() throws Exception {
        TestRealm realm = TestRealm.withEmployer("linked-user");
        var events = new RealmRepresentation();
        events.setAdminEventsEnabled(true);
        ADMIN.realm(realm.name).update(events);
        Api api = realm.api;
        realm.refItem("UNIT_ROLE", "safety", "安全管理者");
        realm.refItem("QUALIFICATION", "license", "看護師免許");
        String ward = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}},"
                + " \"validFrom\": \"2020-04-01\"}"));
        String user = realm.user("nurse");
        String person = id(api.post("/people", "{\"userId\": \"" + user + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"山田 花子\"}}}"));
        JsonNode staff = created(api.post("/staff", "{\"personId\": \"" + person + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2025-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        Map<String, String> records = new LinkedHashMap<>();
        records.put("/employments/" + employment, "userId");
        records.put("/assignments/" + staff.get("assignment").get("id").asText(), "userId");
        records.put("/qualifications/" + id(api.post("/qualifications", "{\"personId\": \"" + person
                + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}")), "userId");
        records.put("/unit-roles/" + id(api.post("/unit-roles", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + ward + "\", \"role\": \"safety\", \"validFrom\": \"2025-04-01\"}")), "userId");
        String clerk = created(api.post("/staff", "{\"userId\": \"" + realm.user("clerk") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2025-04-01\"}")).get("assignment").get("id").asText();
        records.put("/reporting-lines/" + id(api.post("/reporting-lines", "{\"assignmentId\": \"" + clerk
                + "\", \"managerPersonId\": \"" + person + "\", \"validFrom\": \"2025-10-01\"}")), "managerUserId");
        return new Nurse(realm, person, user, records);
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String id(HttpResponse<String> response) throws Exception {
        return created(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String person(String userId) {
        return "{" + (userId == null ? "" : "\"userId\": \"" + userId + "\", ")
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}}";
    }

    private static long lastSequence(Api api) throws Exception {
        JsonNode log = ok(api.get("/change-log?max=1000"));
        return log.isEmpty() ? 0 : log.get(log.size() - 1).get("sequence").asLong();
    }

    private static Map<String, Long> versions(Nurse nurse) throws Exception {
        Map<String, Long> versions = new LinkedHashMap<>();
        for (String record : nurse.records().keySet()) {
            versions.put(record, nurse.api().version(record));
        }
        return versions;
    }

    // The user that each record reads, or an empty text for none.
    private static List<String> usersOf(Nurse nurse) throws Exception {
        List<String> users = new ArrayList<>();
        for (Map.Entry<String, String> record : nurse.records().entrySet()) {
            users.add(ok(nurse.api().get(record.getKey())).path(record.getValue()).asText());
        }
        return users;
    }

    private static List<String> adminEvents(Nurse nurse) {
        return ADMIN.realm(nurse.realm().name).getAdminEvents().stream()
                .filter(event -> event.getResourceType().startsWith("FUHITO_"))
                .map(event -> event.getOperationType() + " " + event.getResourcePath())
                .sorted()
                .toList();
    }

    // A record by its type and its path below the realm, as an entry of the change log names it.
    private static Set<String> recordsInLog(Nurse nurse) {
        return nurse.records().keySet().stream()
                .map(record -> switch (record.split("/", -1)[1]) {
                    case "employments" -> "FUHITO_EMPLOYMENT";
                    case "assignments" -> "FUHITO_ASSIGNMENT";
                    case "qualifications" -> "FUHITO_QUALIFICATION";
                    case "unit-roles" -> "FUHITO_UNIT_ROLE";
                    default -> "FUHITO_REPORTING_LINE";
                } + " fuhito" + record)
                .collect(Collectors.toSet());
    }

    // The entries after a number, with the entry of the write first and the propagated changes after it.
    private static void assertPropagated(Nurse nurse, long after, String changeOrderId, String path)
            throws Exception {
        JsonNode log = ok(nurse.api().get("/change-log?after=" + after));
        JsonNode write = log.get(0);
        assertEquals(List.of("WRITE", "FUHITO_PERSON", nurse.person(), path),
                List.of(write.get("kind").asText(), write.get("resourceType").asText(),
                        write.get("resourceId").asText(), write.get("resourcePath").asText()));
        assertTrue(write.has("actorUserId"));
        assertEquals(changeOrderId, write.path("changeOrderId").asText(null));
        List<JsonNode> propagated = log.valueStream().skip(1).toList();
        assertEquals(recordsInLog(nurse), propagated.stream()
                .map(entry -> entry.get("resourceType").asText() + " " + entry.get("resourcePath").asText())
                .collect(Collectors.toSet()));
        for (JsonNode entry : propagated) {
            assertEquals(List.of("PROPAGATED", "UPDATE", "WRITE", write.get("sequence").asText()),
                    List.of(entry.get("kind").asText(), entry.get("operation").asText(),
                            entry.get("cause").asText(), entry.get("causeSequence").asText()));
            assertEquals(changeOrderId, entry.path("changeOrderId").asText(null));
            assertFalse(entry.has("actorUserId"));
        }
    }

    @Test
    void anUnlinkAndALinkGiveEachRecordOfThePersonANewVersionAndAPropagatedChange() throws Exception {
        Nurse nurse = nurse();
        Api api = nurse.api();
        String order = id(api.post("/change-orders", "{\"title\": \"アカウントの付け替え\", "
                + "\"effectiveDate\": \"2026-04-01\"}"));
        String other = nurse.realm().user("nurse-2");
        List<String> eventsBefore = adminEvents(nurse);

        List<String> users = new ArrayList<>();
        for (String userId : Arrays.asList(null, other)) {
            Map<String, Long> versions = versions(nurse);
            long last = lastSequence(api);
            DerivedReads reads = DerivedReads.before(api);

            ok(api.putWithVersion("/people/" + nurse.person() + "?changeOrder=" + order, person(userId)));

            reads.assertEveryChangeIsLogged();
            versions.replaceAll((record, version) -> version + 1);
            assertEquals(versions, versions(nurse));
            assertPropagated(nurse, last, order, "fuhito/people/" + nurse.person());
            users.addAll(usersOf(nurse));
        }

        assertEquals(List.of("", "", "", "", "", other, other, other, other, other), users);
        // The writes record admin events, and the propagated changes record none.
        List<String> events = new ArrayList<>(adminEvents(nurse));
        eventsBefore.forEach(events::remove);
        assertEquals(List.of("UPDATE fuhito/people/" + nurse.person(), "UPDATE fuhito/people/" + nurse.person()),
                events);
        // The change order lists the propagated changes of its writes too.
        assertEquals(12, api.count("/change-orders/" + order + "/changes/count"));
    }

    @Test
    void aWriteThatKeepsTheUserChangesNoRecordOfThePerson() throws Exception {
        Nurse nurse = nurse();
        Api api = nurse.api();
        Map<String, Long> versions = versions(nurse);
        long propagated = api.count("/change-log/count?kind=PROPAGATED");

        ok(api.putWithVersion("/people/" + nurse.person(), "{\"userId\": \"" + nurse.user() + "\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"佐藤 花子\"}}}"));

        assertEquals(versions, versions(nurse));
        assertEquals(propagated, api.count("/change-log/count?kind=PROPAGATED"));
    }

    @Test
    void aClientThatHoldsAnOldReadOfAnAssignmentGets409AfterALinkOfTheUser() throws Exception {
        Nurse nurse = nurse();
        Api api = nurse.api();
        String assignment = nurse.records().keySet().stream().filter(record -> record.startsWith("/assignments/"))
                .findFirst().orElseThrow();
        long read = api.version(assignment);

        ok(api.putWithVersion("/people/" + nurse.person(), person(nurse.realm().user("nurse-2"))));

        HttpResponse<String> stale = api.put(assignment + "/details", "{\"version\": " + read
                + ", \"validFrom\": \"2025-10-01\", \"head\": true}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("version.conflict", Api.json(stale).get("error").asText());
    }

    @Test
    void anImportThatChangesTheUserOfAPersonGivesEachRecordAPropagatedChange() throws Exception {
        Nurse nurse = nurse();
        Api api = nurse.api();
        String other = nurse.realm().user("nurse-2");
        Map<String, Long> versions = versions(nurse);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        JsonNode report = ok(api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"people\": [{\"id\": \""
                + nurse.person() + "\", \"userId\": \"" + other + "\"}]}"));

        assertEquals(1, report.get("counts").get("people").get("changed").asInt());
        reads.assertEveryChangeIsLogged();
        versions.replaceAll((record, version) -> version + 1);
        assertEquals(versions, versions(nurse));
        assertPropagated(nurse, last, null, "fuhito/people/" + nurse.person());
        assertEquals(List.of(other, other, other, other, other), usersOf(nurse));
    }

    // The person keeps the link to a removed user, so that every read about the user still works. No read of the
    // user changes, so the removal adds no propagated change.
    @Test
    void theRemovalOfTheUserInKeycloakKeepsTheUserInEveryRecord() throws Exception {
        Nurse nurse = nurse();
        Api api = nurse.api();
        String qualification = nurse.records().keySet().stream()
                .filter(record -> record.startsWith("/qualifications/")).findFirst().orElseThrow();
        long version = api.version(qualification);

        nurse.realm().removeUser(nurse.user());

        assertEquals(List.of(nurse.user(), nurse.user(), nurse.user(), nurse.user(), nurse.user()), usersOf(nurse));
        assertEquals(0, api.count("/change-log/count?kind=PROPAGATED"));
        // Qualifications stay as they are.
        assertEquals(version, api.version(qualification));
    }
}

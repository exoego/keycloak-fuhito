package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.AdminEventRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEventsIT {

    @Test
    void everySuccessfulWriteRecordsAnAdminEvent() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var realm = new RealmRepresentation();
        realm.setRealm("events-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setAdminEventsEnabled(true);
        realm.setAdminEventsDetailsEnabled(true);
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(admin, realm);
        Api api = new Api(realm.getRealm(), admin.tokenManager().getAccessTokenString());

        String typeId = Api.json(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"dept\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"部\"}}}")).get("id").asText();
        api.putWithVersion("/ref-items/" + typeId, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"部門\"}}}");
        String parentId = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"本部\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String unitId = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"経理部\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        api.putWithVersion("/org-units/" + unitId, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"財務部\"}}, \"validFrom\": \"2027-04-01\"}");
        api.putWithVersion("/org-units/" + unitId + "/parent", "{\"parentId\": \"" + parentId + "\", \"validFrom\": \"2028-04-01\"}");
        api.putWithVersion("/org-units/" + unitId + "/end", "{\"validTo\": \"2029-04-01\"}");
        // Failed requests and reads record nothing.
        assertEquals(404, api.putWithVersion("/org-units/" + unitId + "/end", "{\"validTo\": \"2026-01-01\"}").statusCode());
        assertEquals(400, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \" \"}}, \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(200, api.get("/org-units?asOf=2026-04-01").statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-items/" + typeId).statusCode());

        List<AdminEventRepresentation> events = admin.realm(realm.getRealm()).getAdminEvents().stream()
                .filter(e -> e.getResourceType().startsWith("FUHITO_"))
                .toList();

        // Two events can have the same time, so the order is not compared.
        assertEquals(Stream.of(
                "CREATE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "UPDATE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "DELETE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "CREATE FUHITO_ORG_UNIT fuhito/org-units/" + parentId,
                "CREATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId,
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId,
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId + "/parent",
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId + "/end").sorted().toList(),
                events.stream()
                        .map(e -> e.getOperationType() + " " + e.getResourceType() + " " + e.getResourcePath())
                        .sorted()
                        .toList());
        AdminEventRepresentation rename = events.stream()
                .filter(e -> e.getOperationType().equals("UPDATE")
                        && e.getResourcePath().equals("fuhito/org-units/" + unitId))
                .findFirst()
                .orElseThrow();
        assertTrue(rename.getRepresentation().contains("財務部"), rename.getRepresentation());
        assertTrue(events.stream().allMatch(e -> e.getAuthDetails().getUserId() != null));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static TestRealm withEventDetails(String prefix) {
        TestRealm realm = TestRealm.withEmployer(prefix);
        var events = new RealmRepresentation();
        events.setAdminEventsEnabled(true);
        events.setAdminEventsDetailsEnabled(true);
        KEYCLOAK.getKeycloakAdminClient().realm(realm.name).update(events);
        return realm;
    }

    @Test
    void aWriteOfAnIdOfAnotherSystemRecordsAnUpdateOfItsResourceWithEveryIdAfterIt() throws Exception {
        TestRealm realm = withEventDetails("events-external-id");
        Api api = realm.api;
        String ward = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"1病棟\"}}, \"validFrom\": \"2020-04-01\", \"externalId\": \"W-1\", \"source\": \"hr\"}"));
        String ids = "/external-ids?resourceType=ORG_UNIT&resourceId=" + ward;
        String put = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"}";
        List<JsonNode> afterEach = new ArrayList<>();

        assertEquals(200, api.putWithVersion("/external-ids", put.formatted("P-9")).statusCode());
        afterEach.add(Api.json(api.get(ids)));
        assertEquals(200, api.putWithVersion("/external-ids", put.formatted("P-10")).statusCode());
        afterEach.add(Api.json(api.get(ids)));
        assertEquals(204, api.deleteWithVersion(ids + "&source=payroll").statusCode());
        afterEach.add(Api.json(api.get(ids)));
        assertEquals(204, api.deleteWithVersion(ids + "&source=hr").statusCode());
        afterEach.add(Api.json(api.get(ids)));

        String path = "fuhito/org-units/" + ward;
        assertEquals(List.of("CREATE " + path, "UPDATE " + path, "UPDATE " + path, "UPDATE " + path, "UPDATE " + path),
                Api.json(api.get("/change-log?resourceId=" + ward)).valueStream()
                        .map(entry -> entry.get("operation").asText() + " " + entry.get("resourcePath").asText())
                        .toList());
        List<AdminEventRepresentation> events = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).getAdminEvents()
                .stream()
                .filter(e -> e.getResourceType().equals("FUHITO_ORG_UNIT") && e.getResourcePath().equals(path))
                .toList();
        assertEquals(List.of("CREATE", "UPDATE", "UPDATE", "UPDATE", "UPDATE"),
                events.stream().map(AdminEventRepresentation::getOperationType).sorted().toList());
        // Two events can have the same time, so the order is not compared.
        Set<JsonNode> recorded = new HashSet<>();
        for (AdminEventRepresentation event : events) {
            if (event.getOperationType().equals("UPDATE")) {
                recorded.add(JSON.readTree(event.getRepresentation()));
            }
        }
        assertEquals(Set.copyOf(afterEach), recorded);
        assertEquals(List.of("hr=W-1", "payroll=P-9"), afterEach.getFirst().valueStream()
                .map(id -> id.get("source").asText() + "=" + id.get("externalId").asText())
                .toList());
        assertTrue(afterEach.getLast().isEmpty(), afterEach.getLast().toString());
    }

    // Makes a resource of each kind that can have IDs of other systems. Each row has the kind, the ID that the IDs
    // name, and the ID in the path. A kind of reference data has its code in the path.
    private static String[][] resourceOfEachKind(TestRealm realm) throws Exception {
        Api api = realm.api;
        String unit = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}"));
        HttpResponse<String> hired = api.post("/staff", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, hired.statusCode(), hired.body());
        JsonNode staff = Api.json(hired);
        String employment = staff.get("employment").get("id").asText();
        String person = staff.get("employment").get("personId").asText();
        String assignment = staff.get("assignment").get("id").asText();
        String license = realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        String line = created(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + realm.user("boss") + "\", \"validFrom\": \"2021-04-01\"}"));
        String role = created(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}"));
        String order = created(api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}"));
        String kind = created(api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"拠点\"}}}"));
        String hierarchy = created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String definition = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": "
                + "\"beds\", \"type\": \"NUMBER\"}"));
        return new String[][] {{"ORG_UNIT", unit, unit}, {"PERSON", person, person},
                {"EMPLOYMENT", employment, employment}, {"ASSIGNMENT", assignment, assignment},
                {"QUALIFICATION", qualification, qualification}, {"REPORTING_LINE", line, line},
                {"UNIT_ROLE", role, role}, {"CHANGE_ORDER", order, order}, {"REF_ITEM", license, license},
                {"REF_KIND", kind, "site"}, {"HIERARCHY", hierarchy, hierarchy},
                {"ATTRIBUTE_DEFINITION", definition, definition}};
    }

    @Test
    void aWriteOfAnIdOfEveryKindOfResourceRecordsAnUpdateOfThatResourceAndNoneOfTheId() throws Exception {
        TestRealm realm = withEventDetails("events-external-id-kinds");
        Api api = realm.api;
        String[][] resources = resourceOfEachKind(realm);
        // The type and the path that the create of each resource recorded, by kind.
        Map<String, String> types = new HashMap<>();
        Map<String, String> paths = new HashMap<>();
        for (String[] resource : resources) {
            JsonNode create = Api.json(api.get("/change-log?resourceId=" + resource[2])).get(0);
            assertEquals("CREATE", create.get("operation").asText(), resource[0]);
            types.put(resource[0], create.get("resourceType").asText());
            paths.put(resource[0], create.get("resourcePath").asText());
        }
        JsonNode setup = Api.json(api.get("/change-log?max=1000"));
        long last = setup.get(setup.size() - 1).get("sequence").asLong();
        int eventsBefore = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).getAdminEvents().size();
        // The IDs that each write leaves, by kind.
        Map<String, List<JsonNode>> afterEach = new HashMap<>();

        for (String[] resource : resources) {
            String ids = "/external-ids?resourceType=" + resource[0] + "&resourceId=" + resource[1];
            List<JsonNode> written = afterEach.computeIfAbsent(resource[0], any -> new ArrayList<>());
            HttpResponse<String> put = api.putWithVersion("/external-ids", "{\"resourceType\": \"" + resource[0]
                    + "\", \"resourceId\": \"" + resource[1] + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}");
            assertEquals(200, put.statusCode(), put.body());
            written.add(Api.json(api.get(ids)));
            assertEquals(204, api.deleteWithVersion(ids + "&source=payroll").statusCode(), resource[0]);
            written.add(Api.json(api.get(ids)));
        }
        // A row of an import that stores an ID, and one that removes it.
        for (String externalId : List.of("\"S-1\"", "null")) {
            StringJoiner rows = new StringJoiner(", ");
            for (String[] resource : resources) {
                rows.add("{\"resourceType\": \"" + resource[0] + "\", \"resourceId\": \"" + resource[1]
                        + "\", \"externalId\": " + externalId + "}");
            }
            HttpResponse<String> imported = api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"source\": "
                    + "\"sheet\", \"externalIds\": [" + rows + "]}");
            assertEquals(200, imported.statusCode(), imported.body());
            for (String[] resource : resources) {
                afterEach.get(resource[0]).add(Api.json(api.get("/external-ids?resourceType=" + resource[0]
                        + "&resourceId=" + resource[1])));
            }
        }

        // Each write records one entry of the resource, with the type, the ID, and the path of its create.
        Map<String, Long> wanted = new HashMap<>();
        for (String[] resource : resources) {
            wanted.put("WRITE UPDATE " + types.get(resource[0]) + " " + resource[2] + " " + paths.get(resource[0]),
                    4L);
        }
        assertEquals(wanted, Api.json(api.get("/change-log?max=1000&after=" + last)).valueStream()
                .collect(Collectors.groupingBy(entry -> entry.get("kind").asText() + " "
                        + entry.get("operation").asText() + " " + entry.get("resourceType").asText() + " "
                        + entry.get("resourceId").asText() + " " + entry.get("resourcePath").asText(),
                        Collectors.counting())));
        // Its admin event has the IDs of the resource after the write, as GET /external-ids lists them.
        List<AdminEventRepresentation> events = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).getAdminEvents();
        assertEquals(eventsBefore + 4 * resources.length, events.size());
        Map<String, Map<JsonNode, Long>> recorded = new HashMap<>();
        for (AdminEventRepresentation event : events) {
            if (event.getOperationType().equals("UPDATE")) {
                recorded.computeIfAbsent(event.getResourceType() + " " + event.getResourcePath(),
                        any -> new HashMap<>()).merge(JSON.readTree(event.getRepresentation()), 1L, Long::sum);
            }
        }
        Map<String, Map<JsonNode, Long>> expected = new HashMap<>();
        afterEach.forEach((kindOfResource, lists) -> expected.put(
                types.get(kindOfResource) + " " + paths.get(kindOfResource),
                lists.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))));
        assertEquals(expected, recorded);
        assertTrue(events.stream().noneMatch(e -> e.getResourceType().equals("FUHITO_EXTERNAL_ID")));
    }
}

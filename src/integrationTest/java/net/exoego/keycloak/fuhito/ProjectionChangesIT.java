package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.GroupRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs of the changes of the projection. After each of them, a full rebuild must find nothing more to do.
 */
class ProjectionChangesIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CHANGES = List.of("groupsCreated", "groupsRenamed", "groupsMoved",
            "groupsRemoved", "membersJoined", "membersLeft", "organizationMembersAdded", "organizationMembersRemoved");

    private TestRealm realm;
    private Api api;

    private void createRealm(Map<String, String> attributes) {
        realm = TestRealm.withEmployer("projection-changes", attributes);
        api = realm.api;
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 204,
                response.statusCode() + " " + response.body());
    }

    private String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return id(api.post("/org-units", "{\"name\": \"" + name + "\"" + parent
                + ", \"validFrom\": \"2020-04-01\"}"));
    }

    // Returns the employment and the assignment of a new member of staff.
    private JsonNode staff(String userId, String orgUnitId) throws Exception {
        HttpResponse<String> response = api.post("/staff", "{\"userId\": \"" + userId + "\", \"orgUnitId\": \""
                + orgUnitId + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private String assignmentOf(JsonNode staff) {
        return staff.get("assignment").get("id").asText();
    }

    private void move(String assignmentId, String orgUnitId) throws Exception {
        ok(api.postWithVersion("/assignments/" + assignmentId + "/move", "{\"orgUnitId\": \"" + orgUnitId
                + "\", \"validFrom\": \"2021-04-01\"}"));
    }

    private void link(String unitId, String organizationId) throws Exception {
        ok(api.putWithVersion("/org-units/" + unitId + "/organization",
                "{\"organizationId\": \"" + organizationId + "\"}"));
    }

    private JsonNode rebuild(String query) throws Exception {
        HttpResponse<String> response = api.post("/projection/rebuild" + query, "");
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private JsonNode changes() throws Exception {
        JsonNode report = rebuild("");
        assertFalse(report.get("fellBack").asBoolean(), report.toString());
        return report;
    }

    // After a run of the changes, a full rebuild finds nothing more to do.
    private void assertSettled() throws Exception {
        JsonNode full = rebuild("?full=true");
        for (String count : CHANGES) {
            assertEquals(0, full.get(count).asInt(), count + " " + full);
        }
    }

    // The admin API of Keycloak, not of the extension.
    private JsonNode keycloak(String path) throws Exception {
        String token = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm.name + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " " + response.body());
        return JSON.readTree(response.body());
    }

    private static List<String> sorted(JsonNode list, String field) {
        return list.findValuesAsText(field).stream().sorted().toList();
    }

    private List<String> members(String organizationId) throws Exception {
        return sorted(keycloak("/organizations/" + organizationId + "/members"), "username");
    }

    // Each group below a base as its path and its members, at every depth.
    private List<String> groups(String base, String topPath) throws Exception {
        List<String> result = new ArrayList<>();
        collect(base, keycloak(topPath), "", result);
        return result.stream().sorted().toList();
    }

    private void collect(String base, JsonNode groups, String path, List<String> result) throws Exception {
        for (JsonNode group : groups) {
            String groupId = group.get("id").asText();
            String name = path + "/" + group.get("name").asText();
            result.add(name + " " + sorted(keycloak(base + "/" + groupId + "/members"), "username"));
            collect(base, keycloak(base + "/" + groupId + "/children"), name, result);
        }
    }

    private List<String> organizationGroups(String organizationId) throws Exception {
        String base = "/organizations/" + organizationId + "/groups";
        return groups(base, base);
    }

    @Test
    void followsPeopleWhoMoveInsideAndAcrossOrganizationsComeAndLeave() throws Exception {
        createRealm(Map.of("fuhito.projection.enabled", "true"));
        String xOrganization = realm.organization("x-hospital");
        String yOrganization = realm.organization("y-hospital");
        String xHospital = unit("X病院", null);
        String east = unit("東病棟", xHospital);
        String west = unit("西病棟", xHospital);
        String yHospital = unit("Y病院", null);
        String yWard = unit("Y病棟", yHospital);
        String stays = realm.user("stays");
        JsonNode staysStaff = staff(stays, east);
        String inside = assignmentOf(staff(realm.user("moves-inside"), east));
        String across = assignmentOf(staff(realm.user("moves-across"), east));
        String leaves = staff(realm.user("leaves"), west).get("employment").get("id").asText();
        String mistake = assignmentOf(staff(realm.user("mistake"), west));
        link(xHospital, xOrganization);
        link(yHospital, yOrganization);

        // No run was done before, so the first one is a full rebuild.
        JsonNode first = rebuild("");
        assertTrue(first.get("fellBack").asBoolean(), first.toString());
        assertEquals("no_earlier_run", first.get("fallbackReason").asText());
        assertEquals(5, first.get("organizationMembersAdded").asInt());

        move(inside, west);
        move(across, yWard);
        staff(realm.user("joins"), yWard);
        ok(api.putWithVersion("/employments/" + leaves + "/end", "{\"validTo\": \"2021-04-01\"}"));
        ok(api.deleteWithVersion("/assignments/" + mistake));
        // A new name changes nothing that the projection mirrors, but its user is looked at again.
        String person = staysStaff.get("employment").get("personId").asText();
        ok(api.putWithVersion("/people/" + person, "{\"userId\": \"" + stays + "\", \"name\": \"Stays\"}"));

        JsonNode report = changes();

        assertEquals(6, report.get("usersLookedAt").asInt(), report.toString());
        assertEquals(2, report.get("organizationsLookedAt").asInt());
        assertEquals(List.of(3, 4, 2, 3), List.of(report.get("membersJoined").asInt(),
                report.get("membersLeft").asInt(), report.get("organizationMembersAdded").asInt(),
                report.get("organizationMembersRemoved").asInt()), report.toString());
        assertEquals(List.of("moves-inside", "stays"), members(xOrganization));
        assertEquals(List.of("joins", "moves-across"), members(yOrganization));
        assertEquals(List.of("/東病棟 [stays]", "/西病棟 [moves-inside]"), organizationGroups(xOrganization));
        assertSettled();
        // The last run is the full rebuild.
        assertFalse(Api.json(api.get("/projection/last-run")).get("report").has("changeLogEntries"));
    }

    @Test
    void followsUnitsThatMoveEndAndChangeTheirLinksInOrganizationsAndRealmGroups() throws Exception {
        createRealm(Map.of("fuhito.projection.enabled", "true", "fuhito.projection.realmGroup", "/org"));
        GroupRepresentation org = new GroupRepresentation();
        org.setName("org");
        String orgGroup = CreatedResponseUtil.getCreatedId(KEYCLOAK.getKeycloakAdminClient().realm(realm.name)
                .groups().add(org));
        String xOrganization = realm.organization("x-hospital");
        String yOrganization = realm.organization("y-clinic");
        String zOrganization = realm.organization("z-hospital");
        String xHospital = unit("X病院", null);
        String nursing = unit("看護部", xHospital);
        String ward = unit("1病棟", nursing);
        unit("ICU", nursing);
        String medicine = unit("内科", xHospital);
        String office = unit("事務室", xHospital);
        String yClinic = unit("Yクリニック", null);
        String zHospital = unit("Z病院", null);
        staff(realm.user("nurse"), ward);
        String clerk = assignmentOf(staff(realm.user("clerk"), office));
        staff(realm.user("receptionist"), unit("受付", yClinic));
        staff(realm.user("z-nurse"), unit("Z病棟", zHospital));
        link(xHospital, xOrganization);
        link(zHospital, zOrganization);
        rebuild("?full=true");

        ok(api.putWithVersion("/org-units/" + ward, "{\"name\": \"第一病棟\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.putWithVersion("/org-units/" + nursing + "/parent",
                "{\"parentId\": \"" + medicine + "\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.putWithVersion("/assignments/" + clerk + "/end", "{\"validTo\": \"2021-04-01\"}"));
        ok(api.putWithVersion("/org-units/" + office + "/end", "{\"validTo\": \"2021-04-01\"}"));
        link(yClinic, yOrganization);
        ok(api.deleteWithVersion("/org-units/" + zHospital + "/organization"));

        JsonNode report = changes();

        assertEquals(3, report.get("organizationsLookedAt").asInt(), report.toString());
        assertEquals(1, report.get("realmGroupTrees").asInt());
        assertEquals(List.of("/内科 []", "/内科/看護部 []", "/内科/看護部/ICU []", "/内科/看護部/第一病棟 [nurse]"),
                organizationGroups(xOrganization));
        assertEquals(List.of("nurse"), members(xOrganization));
        assertEquals(List.of("/受付 [receptionist]"), organizationGroups(yOrganization));
        assertEquals(List.of("receptionist"), members(yOrganization));
        assertEquals(List.of(), organizationGroups(zOrganization));
        assertEquals(List.of(), members(zOrganization));
        assertTrue(groups("/groups", "/groups/" + orgGroup + "/children")
                .contains("/X病院/内科/看護部/第一病棟 [nurse]"));
        assertSettled();
    }

    @Test
    void aRestoreMakesTheNextRunOfTheChangesAFullRebuild() throws Exception {
        createRealm(Map.of("fuhito.projection.enabled", "true"));
        String organization = realm.organization("x-hospital");
        String hospital = unit("X病院", null);
        String ward = unit("病棟", hospital);
        staff(realm.user("nurse"), ward);
        link(hospital, organization);
        rebuild("?full=true");
        var export = RealmExportIT.export(api);
        ok(api.post("/restore?replace=true", JSON.writeValueAsString(export)));
        staff(realm.user("joins"), ward);

        JsonNode report = rebuild("");

        assertTrue(report.get("fellBack").asBoolean(), report.toString());
        assertEquals("restore", report.get("fallbackReason").asText());
        assertEquals(1, report.get("membersJoined").asInt());

        JsonNode next = changes();
        assertEquals(0, next.get("changeLogEntries").asInt());
        assertEquals(0, next.get("usersLookedAt").asInt());
        JsonNode lastRun = Api.json(api.get("/projection/last-run")).get("report");
        assertEquals(false, lastRun.get("fellBack").asBoolean());
        assertEquals(0, lastRun.get("changeLogEntries").asInt());
        assertSettled();
    }
}

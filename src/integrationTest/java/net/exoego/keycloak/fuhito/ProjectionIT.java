package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the result of the projection through the admin API of Keycloak itself.
 */
class ProjectionIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Keycloak admin;
    private String realm;
    private String token;
    private Api api;

    private void createRealm(boolean projection) {
        admin = KEYCLOAK.getKeycloakAdminClient();
        token = admin.tokenManager().getAccessTokenString();
        var representation = new RealmRepresentation();
        realm = "projection-" + UUID.randomUUID();
        representation.setRealm(realm);
        representation.setEnabled(true);
        representation.setOrganizationsEnabled(true);
        if (projection) {
            representation.setAttributes(Map.of("fuhito.projection.enabled", "true"));
        }
        TestRealm.withTimeZone(representation);
        admin.realms().create(representation);
        api = new Api(realm, token);
    }

    private String user(String username) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = admin.realm(realm).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String post(String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = Api.json(response);
        return json.has("id") ? json.get("id").asText() : json.get("assignment").get("id").asText();
    }

    private String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return post("/org-units", "{\"name\": \"" + name + "\"" + parent + ", \"validFrom\": \"2020-04-01\"}");
    }

    private String staff(String userId, String orgUnitId) throws Exception {
        return post("/staff", "{\"userId\": \"" + userId + "\", \"orgUnitId\": \"" + orgUnitId
                + "\", \"validFrom\": \"2020-04-01\"}");
    }

    // The admin API of Keycloak, not of the extension.
    private JsonNode keycloak(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " " + response.body());
        return Api.json(response);
    }

    private JsonNode rebuild() throws Exception {
        HttpResponse<String> response = api.post("/projection/rebuild", "");
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String idOf(JsonNode groups, String name) {
        for (JsonNode group : groups) {
            if (group.get("name").asText().equals(name)) {
                return group.get("id").asText();
            }
        }
        throw new AssertionError("no group " + name + " in " + groups);
    }

    private List<String> sorted(JsonNode list, String field) {
        return list.findValuesAsText(field).stream().sorted().toList();
    }

    @Test
    void mirrorsAHospitalIntoOrganizationGroupsAndFollowsChanges() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        String nursing = unit("看護部", hospital);
        String ward = unit("1病棟", nursing);
        String office = unit("事務室", hospital);
        String director = user("director");
        String headNurse = user("head-nurse");
        String nurse = user("nurse");
        String clerk = user("clerk");
        staff(director, hospital);
        staff(headNurse, nursing);
        String nurseAssignment = staff(nurse, ward);
        staff(clerk, office);
        assertEquals(200, api.put("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());

        JsonNode first = rebuild();

        assertEquals(1, first.get("organizations").asInt());
        assertEquals(3, first.get("groupsCreated").asInt());
        assertEquals(3, first.get("membersJoined").asInt());
        assertEquals(4, first.get("organizationMembersAdded").asInt());
        String groups = "/organizations/" + organization + "/groups";
        JsonNode top = keycloak(groups);
        assertEquals(List.of("事務室", "看護部"), sorted(top, "name"));
        String nursingGroup = idOf(top, "看護部");
        JsonNode children = keycloak(groups + "/" + nursingGroup + "/children");
        assertEquals(List.of("1病棟"), sorted(children, "name"));
        String wardGroup = idOf(children, "1病棟");
        assertEquals(List.of("head-nurse"), sorted(keycloak(groups + "/" + nursingGroup + "/members"), "username"));
        assertEquals(List.of("nurse"), sorted(keycloak(groups + "/" + wardGroup + "/members"), "username"));
        JsonNode members = keycloak("/organizations/" + organization + "/members");
        assertEquals(List.of("clerk", "director", "head-nurse", "nurse"), sorted(members, "username"));
        assertTrue(members.findValuesAsText("membershipType").stream().allMatch("UNMANAGED"::equals), members.toString());

        // A second rebuild with no change does nothing.
        JsonNode again = rebuild();
        for (String count : List.of("groupsCreated", "groupsRenamed", "groupsMoved", "groupsRemoved", "membersJoined",
                "membersLeft", "organizationMembersAdded")) {
            assertEquals(0, again.get(count).asInt(), count);
        }

        // The ward moves to the top with a new name, the office ends, and the nurse leaves the ward.
        assertEquals(200, api.put("/org-units/" + ward + "/parent", "{\"validFrom\": \"2021-04-01\", \"parentId\": \"" + hospital + "\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + ward, "{\"name\": \"東病棟\", \"validFrom\": \"2021-04-01\"}").statusCode());
        assertEquals(200, api.put("/assignments/" + nurseAssignment + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());
        JsonNode clerkAssignments = Api.json(api.get("/users/" + clerk + "/assignments"));
        assertEquals(200, api.put("/assignments/" + clerkAssignments.get(0).get("assignmentId").asText() + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + office + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());

        JsonNode second = rebuild();

        assertEquals(1, second.get("groupsMoved").asInt());
        assertEquals(1, second.get("groupsRenamed").asInt());
        assertEquals(1, second.get("groupsRemoved").asInt());
        assertEquals(1, second.get("membersLeft").asInt());
        JsonNode topAfter = keycloak(groups);
        assertEquals(List.of("東病棟", "看護部"), sorted(topAfter, "name"));
        // The moved group is the same group, so what Keycloak attached to it stays.
        assertEquals(wardGroup, idOf(topAfter, "東病棟"));
        assertEquals(0, keycloak(groups + "/" + nursingGroup + "/children").size());
        assertEquals(0, keycloak(groups + "/" + wardGroup + "/members").size());
        // The projection never takes a user out of the organization.
        assertEquals(4, keycloak("/organizations/" + organization + "/members").size());
    }

    @Test
    void aRealmThatDidNotTurnItOnGetsNothing() throws Exception {
        createRealm(false);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        unit("看護部", hospital);
        assertEquals(200, api.put("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());

        HttpResponse<String> response = api.post("/projection/rebuild", "");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("projection.disabled", Api.json(response).get("error").asText());
        assertEquals(0, keycloak("/organizations/" + organization + "/groups").size());
        assertEquals(401, new Api(realm, null).post("/projection/rebuild", "").statusCode());
    }

    @Test
    void oneUserBelongsToTwoOrganizations() throws Exception {
        createRealm(true);
        String xOrganization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String yOrganization = OrganizationLinkIT.createOrganization(admin, realm, "y-hospital");
        String xHospital = unit("X病院", null);
        String yHospital = unit("Y病院", null);
        String xMedicine = unit("内科", xHospital);
        String ySurgery = unit("外科", yHospital);
        String physician = user("physician");
        String employment = post("/employments", "{\"userId\": \"" + physician + "\", \"validFrom\": \"2020-04-01\"}");
        for (String unit : List.of(xMedicine, ySurgery)) {
            post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\"}");
        }
        assertEquals(200, api.put("/org-units/" + xHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + yOrganization + "\"}").statusCode());

        JsonNode report = rebuild();

        assertEquals(2, report.get("organizations").asInt());
        assertEquals(2, report.get("organizationMembersAdded").asInt());
        for (String organization : List.of(xOrganization, yOrganization)) {
            assertEquals(List.of("physician"), sorted(keycloak("/organizations/" + organization + "/members"), "username"));
        }
        String xGroup = idOf(keycloak("/organizations/" + xOrganization + "/groups"), "内科");
        assertEquals(List.of("physician"), sorted(keycloak("/organizations/" + xOrganization + "/groups/" + xGroup + "/members"), "username"));
    }

    @Test
    void aUnitThatMovesBelowAnotherUnitMovesItsGroupThere() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "y-hospital");
        String hospital = unit("Y病院", null);
        String nursing = unit("看護部", hospital);
        String office = unit("事務室", hospital);
        assertEquals(200, api.put("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        rebuild();
        String groups = "/organizations/" + organization + "/groups";
        assertEquals(List.of("事務室", "看護部"), sorted(keycloak(groups), "name"));

        assertEquals(200, api.put("/org-units/" + office + "/parent",
                "{\"parentId\": \"" + nursing + "\", \"validFrom\": \"2021-04-01\"}").statusCode());
        JsonNode moved = rebuild();

        assertEquals(1, moved.get("groupsMoved").asInt());
        JsonNode top = keycloak(groups);
        assertEquals(List.of("看護部"), sorted(top, "name"));
        assertEquals(List.of("事務室"), sorted(keycloak(groups + "/" + idOf(top, "看護部") + "/children"), "name"));
    }

    // Keycloak ends the employments of a user that it removes. Here the user is gone with no such step,
    // as after a restore of only one of the two sets of tables.
    @Test
    void aUserThatIsGoneFromKeycloakIsSkipped() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "z-hospital");
        String hospital = unit("Z病院", null);
        String ward = unit("病棟", hospital);
        String stays = user("stays");
        String gone = user("gone");
        staff(stays, ward);
        staff(gone, ward);
        staff(gone, hospital);
        assertEquals(200, api.put("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        for (String table : List.of("user_role_mapping", "user_group_membership", "user_attribute", "credential",
                "user_required_action")) {
            KeycloakEnvironment.sql("delete from " + table + " where user_id = '" + gone + "'");
        }
        KeycloakEnvironment.sql("delete from user_entity where id = '" + gone + "'");
        admin.realm(realm).clearUserCache();

        JsonNode report = rebuild();

        // Only the user that Keycloak still has is counted.
        assertEquals(1, report.get("membersJoined").asInt());
        assertEquals(1, report.get("organizationMembersAdded").asInt());
        assertEquals(0, rebuild().get("membersJoined").asInt());
        JsonNode groups = keycloak("/organizations/" + organization + "/groups");
        assertEquals(List.of("stays"), sorted(keycloak("/organizations/" + organization + "/groups/"
                + idOf(groups, "病棟") + "/members"), "username"));
    }
}

package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        TestRealm.createRealm(admin, representation);
        api = new Api(realm, token);
        TestRealm.legalEntity(api, "法人");
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

    private void link(String unit, String organization) throws Exception {
        assertEquals(200, api.putWithVersion("/org-units/" + unit + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
    }

    private void endAssignment(String assignment) throws Exception {
        assertEquals(200, api.putWithVersion("/assignments/" + assignment + "/end", "{\"validTo\": \"2021-04-01\"}")
                .statusCode());
    }

    private List<String> members(String organization) throws Exception {
        return sorted(keycloak("/organizations/" + organization + "/members"), "username");
    }

    private void addByHand(String organization, String userId) {
        try (Response response = admin.realm(realm).organizations().get(organization).members().addMember(userId)) {
            assertEquals(201, response.getStatus());
        }
    }

    private void removeByHand(String organization, String userId) {
        try (Response response = admin.realm(realm).organizations().get(organization).members().member(userId)
                .delete()) {
            assertEquals(204, response.getStatus());
        }
    }

    private void organizationsEnabled(boolean enabled) {
        RealmRepresentation representation = admin.realm(realm).toRepresentation();
        representation.setOrganizationsEnabled(enabled);
        admin.realm(realm).update(representation);
    }

    // The ID of the record of a member that the projection added, or an empty text for none.
    private static String record(String organization, String userId) throws Exception {
        return KeycloakEnvironment.sql("select id from fuhito_projected_member where kc_org_id = '" + organization
                + "' and user_id = '" + userId + "'");
    }

    @Test
    void aSecondRebuildIsRefusedWhileOneIsGoingOn() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        String nurse = user("nurse");
        staff(nurse, unit("1病棟", hospital));
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());

        CompletableFuture<HttpResponse<String>> first;
        try (Connection connection = KeycloakEnvironment.connection()) {
            // A new membership needs the row of its user. So the first rebuild waits here.
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from user_entity where id = ? for update")) {
                statement.setString(1, nurse);
                statement.execute();
            }
            first = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.post("/projection/rebuild", "");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            String waiting = "0";
            for (int i = 0; i < 300 && waiting.equals("0"); i++) {
                Thread.sleep(100);
                waiting = KeycloakEnvironment.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'");
            }
            assertTrue(!waiting.equals("0"), "the first rebuild never waited");

            HttpResponse<String> second = api.post("/projection/rebuild", "");

            assertEquals(409, second.statusCode(), second.body());
            assertEquals("projection.running", Api.json(second).get("error").asText());
            connection.rollback();
        }
        assertEquals(200, first.get().statusCode(), first.get().body());
        assertEquals(1, Api.json(first.get()).get("membersJoined").asInt());
        // The lock is free again.
        assertEquals(0, rebuild().get("membersJoined").asInt());
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
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
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
        String officeGroup = idOf(top, "事務室");
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
                "membersLeft", "organizationMembersAdded", "organizationMembersRemoved")) {
            assertEquals(0, again.get(count).asInt(), count);
        }

        // The ward moves to the top with a new name, the office ends, and the nurse leaves the ward.
        // A new office takes the name of the old one on the same date.
        assertEquals(200, api.putWithVersion("/org-units/" + ward + "/parent", "{\"validFrom\": \"2021-04-01\", \"parentId\": \"" + hospital + "\"}").statusCode());
        assertEquals(200, api.putWithVersion("/org-units/" + ward, "{\"name\": \"東病棟\", \"validFrom\": \"2021-04-01\"}").statusCode());
        assertEquals(200, api.putWithVersion("/assignments/" + nurseAssignment + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());
        JsonNode clerkAssignments = Api.json(api.get("/users/" + clerk + "/assignments"));
        assertEquals(200, api.putWithVersion("/assignments/" + clerkAssignments.get(0).get("assignmentId").asText() + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());
        assertEquals(200, api.putWithVersion("/org-units/" + office + "/end", "{\"validTo\": \"2021-04-01\"}").statusCode());
        post("/org-units", "{\"name\": \"事務室\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2021-04-01\"}");

        JsonNode second = rebuild();

        assertEquals(1, second.get("groupsMoved").asInt());
        assertEquals(1, second.get("groupsRenamed").asInt());
        assertEquals(1, second.get("groupsRemoved").asInt());
        assertEquals(1, second.get("groupsCreated").asInt());
        assertEquals(1, second.get("membersLeft").asInt());
        assertEquals(2, second.get("organizationMembersRemoved").asInt());
        JsonNode topAfter = keycloak(groups);
        assertEquals(List.of("事務室", "東病棟", "看護部"), sorted(topAfter, "name"));
        assertNotEquals(officeGroup, idOf(topAfter, "事務室"));
        // The moved group is the same group, so what Keycloak attached to it stays.
        assertEquals(wardGroup, idOf(topAfter, "東病棟"));
        assertEquals(0, keycloak(groups + "/" + nursingGroup + "/children").size());
        assertEquals(0, keycloak(groups + "/" + wardGroup + "/members").size());
        // The nurse and the clerk have no assignment today, so they left the organization too.
        assertEquals(List.of("director", "head-nurse"), members(organization));
    }

    @Test
    void aRealmThatDidNotTurnItOnGetsNothing() throws Exception {
        createRealm(false);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        unit("看護部", hospital);
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
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
        assertEquals(200, api.putWithVersion("/org-units/" + xHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}").statusCode());
        assertEquals(200, api.putWithVersion("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + yOrganization + "\"}").statusCode());

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
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        rebuild();
        String groups = "/organizations/" + organization + "/groups";
        assertEquals(List.of("事務室", "看護部"), sorted(keycloak(groups), "name"));

        assertEquals(200, api.putWithVersion("/org-units/" + office + "/parent",
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
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
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

    @Test
    void aPersonWhoMovesToAUnitOfAnotherOrganizationLeavesTheOldOne() throws Exception {
        createRealm(true);
        String xOrganization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String yOrganization = OrganizationLinkIT.createOrganization(admin, realm, "y-hospital");
        String xHospital = unit("X病院", null);
        String yHospital = unit("Y病院", null);
        String xWard = unit("X病棟", xHospital);
        String yWard = unit("Y病棟", yHospital);
        String nurse = user("nurse");
        String assignment = staff(nurse, xWard);
        link(xHospital, xOrganization);
        link(yHospital, yOrganization);
        rebuild();
        assertEquals(List.of("nurse"), members(xOrganization));
        assertNotEquals("", record(xOrganization, nurse));

        assertEquals(201, api.postWithVersion("/assignments/" + assignment + "/move",
                "{\"orgUnitId\": \"" + yWard + "\", \"validFrom\": \"2021-04-01\"}").statusCode());
        JsonNode report = rebuild();

        assertEquals(1, report.get("organizationMembersAdded").asInt());
        assertEquals(1, report.get("organizationMembersRemoved").asInt());
        assertEquals(List.of(), members(xOrganization));
        assertEquals(List.of("nurse"), members(yOrganization));
        assertEquals("", record(xOrganization, nurse));
        assertNotEquals("", record(yOrganization, nurse));
        String xGroup = idOf(keycloak("/organizations/" + xOrganization + "/groups"), "X病棟");
        assertEquals(0, keycloak("/organizations/" + xOrganization + "/groups/" + xGroup + "/members").size());
        assertEquals(1, Api.json(api.get("/projection/last-run")).get("report").get("organizationMembersRemoved")
                .asInt());
    }

    @Test
    void whileTheRealmTurnedOrganizationsOffTheRecordsStayForTheNextRebuild() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String xHospital = unit("X病院", null);
        String yHospital = unit("Y病院", null);
        String nurse = user("nurse");
        String assignment = staff(nurse, xHospital);
        link(xHospital, organization);
        rebuild();
        String recorded = record(organization, nurse);
        assertNotEquals("", recorded);

        organizationsEnabled(false);
        assertEquals(201, api.postWithVersion("/assignments/" + assignment + "/move",
                "{\"orgUnitId\": \"" + yHospital + "\", \"validFrom\": \"2021-04-01\"}").statusCode());
        JsonNode report = rebuild();

        assertEquals(0, report.get("organizations").asInt());
        assertEquals(0, report.get("organizationMembersRemoved").asInt());
        assertEquals(recorded, record(organization, nurse));

        organizationsEnabled(true);
        assertEquals(1, rebuild().get("organizationMembersRemoved").asInt());
        assertEquals(List.of(), members(organization));
        assertEquals("", record(organization, nurse));
    }

    @Test
    void membersThatTheProjectionDidNotAddStay() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        String guest = user("guest");
        String earlier = user("earlier");
        String assignment = staff(earlier, hospital);
        addByHand(organization, guest);
        addByHand(organization, earlier);
        link(hospital, organization);

        // The user with an assignment was a member before the projection would add them.
        assertEquals(0, rebuild().get("organizationMembersAdded").asInt());
        assertEquals("", record(organization, earlier));

        endAssignment(assignment);
        assertEquals(0, rebuild().get("organizationMembersRemoved").asInt());
        assertEquals(List.of("earlier", "guest"), members(organization));
    }

    @Test
    void theMembersOfAnOrganizationWhoseLinkedUnitEndedGo() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "z-clinic");
        String clinic = unit("Zクリニック", null);
        String desk = unit("受付", clinic);
        String receptionist = user("receptionist");
        String assignment = staff(receptionist, desk);
        link(clinic, organization);
        rebuild();
        addByHand(organization, user("guest"));

        endAssignment(assignment);
        for (String unit : List.of(desk, clinic)) {
            assertEquals(200, api.putWithVersion("/org-units/" + unit + "/end", "{\"validTo\": \"2021-04-01\"}")
                    .statusCode());
        }
        JsonNode report = rebuild();

        assertEquals(0, report.get("organizations").asInt());
        assertEquals(1, report.get("groupsRemoved").asInt());
        assertEquals(1, report.get("organizationMembersRemoved").asInt());
        assertEquals(List.of("guest"), members(organization));
        assertEquals("", record(organization, receptionist));
    }

    @Test
    void aMemberThatAnAdministratorTookOutLosesItsRecord() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        String stays = user("stays");
        String leaves = user("leaves");
        staff(stays, hospital);
        String assignment = staff(leaves, hospital);
        link(hospital, organization);
        rebuild();
        String first = record(organization, stays);
        removeByHand(organization, stays);
        removeByHand(organization, leaves);
        endAssignment(assignment);

        JsonNode report = rebuild();

        // The administrator took both out, so the rebuild took nobody out.
        assertEquals(0, report.get("organizationMembersRemoved").asInt());
        assertEquals(1, report.get("organizationMembersAdded").asInt());
        assertEquals(List.of("stays"), members(organization));
        assertNotEquals("", record(organization, stays));
        assertNotEquals(first, record(organization, stays));
        assertEquals("", record(organization, leaves));

        // With no record, a member that the administrator adds again stays.
        addByHand(organization, leaves);
        rebuild();
        assertEquals(List.of("leaves", "stays"), members(organization));
    }

    // Keycloak removes a managed member from the realm. An identity provider adds such members.
    @Test
    void aManagedMemberStaysAndLosesItsRecord() throws Exception {
        createRealm(true);
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = unit("X病院", null);
        String managed = user("managed");
        String assignment = staff(managed, hospital);
        link(hospital, organization);
        rebuild();
        KeycloakEnvironment.sql("update user_group_membership set membership_type = 'MANAGED' where user_id = '"
                + managed + "'");
        admin.realm(realm).clearUserCache();
        endAssignment(assignment);

        assertEquals(0, rebuild().get("organizationMembersRemoved").asInt());

        assertEquals(List.of("managed"), members(organization));
        assertEquals("managed", admin.realm(realm).users().get(managed).toRepresentation().getUsername());
        assertEquals("", record(organization, managed));
    }

    @Test
    void theRemovalOfAUserOrAnOrganizationRemovesItsRecords() throws Exception {
        createRealm(true);
        String xOrganization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String yOrganization = OrganizationLinkIT.createOrganization(admin, realm, "y-hospital");
        String xHospital = unit("X病院", null);
        String yHospital = unit("Y病院", null);
        String removed = user("removed");
        String kept = user("kept");
        for (String userId : List.of(removed, kept)) {
            staff(userId, xHospital);
            staff(userId, yHospital);
        }
        link(xHospital, xOrganization);
        link(yHospital, yOrganization);
        assertEquals(4, rebuild().get("organizationMembersAdded").asInt());

        admin.realm(realm).users().delete(removed).close();

        assertEquals("", record(xOrganization, removed));
        assertEquals("", record(yOrganization, removed));
        assertNotEquals("", record(yOrganization, kept));

        try (Response response = admin.realm(realm).organizations().get(yOrganization).delete()) {
            assertEquals(204, response.getStatus());
        }

        assertEquals("", record(yOrganization, kept));
        assertNotEquals("", record(xOrganization, kept));
    }
}

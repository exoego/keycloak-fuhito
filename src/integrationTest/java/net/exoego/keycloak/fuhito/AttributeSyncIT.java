package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.keycloak.representations.userprofile.config.UPConfig;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User attributes as an identity provider would set them over SCIM. Here the admin API sets them.
 */
class AttributeSyncIT {

    private Keycloak admin;
    private String realm;
    private Api api;

    private void createRealm(Map<String, String> attributes) {
        admin = KEYCLOAK.getKeycloakAdminClient();
        var representation = new RealmRepresentation();
        realm = "attribute-sync-" + UUID.randomUUID();
        representation.setRealm(realm);
        representation.setEnabled(true);
        representation.setAttributes(attributes);
        TestRealm.withTimeZone(representation);
        admin.realms().create(representation);
        // Without this, the user profile of Keycloak drops attributes that it does not know.
        UPConfig profile = admin.realm(realm).users().userProfile().getConfiguration();
        profile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        admin.realm(realm).users().userProfile().update(profile);
        api = new Api(realm, admin.tokenManager().getAccessTokenString());
    }

    private String user(String username, Map<String, String> attributes) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        attributes.forEach(user::singleAttribute);
        try (Response response = admin.realm(realm).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private void setAttributes(String userId, Map<String, String> attributes) {
        UserRepresentation user = admin.realm(realm).users().get(userId).toRepresentation();
        user.setAttributes(null);
        attributes.forEach(user::singleAttribute);
        admin.realm(realm).users().get(userId).update(user);
    }

    private String unit(String name, String code, String parentId) throws Exception {
        String more = (code == null ? "" : ", \"code\": \"" + code + "\"")
                + (parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"");
        HttpResponse<String> response = api.post("/org-units", "{\"name\": \"" + name + "\"" + more + ", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private JsonNode run(String query) throws Exception {
        HttpResponse<String> response = api.post("/attribute-sync/run" + query, "");
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private JsonNode assignmentsOf(String userId) throws Exception {
        return Api.json(api.get("/users/" + userId + "/assignments"));
    }

    @Test
    void buildsAssignmentsFromUserAttributesAndFollowsChanges() throws Exception {
        createRealm(Map.of("fuhito.sync.enabled", "true"));
        String hospital = unit("X病院", null, null);
        String medicine = unit("内科", "naika", hospital);
        String surgery = unit("外科", null, hospital);
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"JOB_TITLE\", \"code\": \"director\", \"name\": \"部長\"}").statusCode());
        String boss = user("boss", Map.of("department", "naika", "title", "部長", "employeeNumber", "E-001"));
        String chief = user("chief", Map.of("department", "外科"));
        String alice = user("alice", Map.of("department", "内科", "manager", boss, "employeeNumber", "E-002"));
        user("no-department", Map.of("title", "部長"));
        String lost = user("lost", Map.of("department", "存在しない部署"));

        JsonNode dryRun = run("?dryRun=true");
        assertTrue(dryRun.get("dryRun").asBoolean());
        assertEquals(3, dryRun.get("assignmentsAdded").asInt());
        assertEquals(0, assignmentsOf(alice).size());

        JsonNode first = run("");

        assertEquals(4, first.get("users").asInt());
        assertEquals(3, first.get("employmentsStarted").asInt());
        assertEquals(3, first.get("assignmentsAdded").asInt());
        assertEquals(1, first.get("reportingLinesChanged").asInt());
        assertEquals(1, first.get("unmatched").size());
        JsonNode unmatched = first.get("unmatched").get(0);
        assertEquals(lost, unmatched.get("userId").asText());
        assertEquals("department", unmatched.get("field").asText());
        assertEquals("存在しない部署", unmatched.get("value").asText());
        assertEquals("unknown", unmatched.get("reason").asText());

        JsonNode ofBoss = assignmentsOf(boss).get(0);
        assertEquals(medicine, ofBoss.get("orgUnitId").asText());
        assertEquals("director", ofBoss.get("jobTitle").asText());
        assertEquals("E-001", ofBoss.get("staffNumber").asText());
        assertTrue(ofBoss.get("primary").asBoolean());
        // The sync uses the date of the server. The container runs in UTC, whatever the zone of this test is.
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), ofBoss.get("validFrom").asText());
        assertEquals(medicine, assignmentsOf(alice).get(0).get("orgUnitId").asText());
        JsonNode managers = Api.json(api.get("/users/" + alice + "/managers"));
        assertEquals(List.of(boss), managers.findValuesAsText("userId"));
        assertFalse(managers.get(0).get("derived").asBoolean());
        assertEquals(0, assignmentsOf(lost).size());

        // The writes are in the change log, like any other write.
        String aliceAssignment = assignmentsOf(alice).get(0).get("assignmentId").asText();
        assertEquals(List.of("CREATE"), Api.json(api.get("/change-log?resourceType=FUHITO_ASSIGNMENT&resourceId=" + aliceAssignment)).findValuesAsText("operation"));

        JsonNode again = run("");
        for (String count : List.of("employmentsStarted", "assignmentsAdded", "assignmentsMoved", "assignmentsChanged",
                "assignmentsEnded", "reportingLinesChanged")) {
            assertEquals(0, again.get(count).asInt(), count);
        }

        // The identity provider moves alice to surgery under the chief, and takes the department from the boss.
        setAttributes(alice, Map.of("department", "外科", "manager", chief));
        setAttributes(boss, Map.of("title", "部長"));

        JsonNode second = run("");

        assertEquals(1, second.get("assignmentsMoved").asInt());
        assertEquals(1, second.get("assignmentsEnded").asInt());
        assertEquals(1, second.get("reportingLinesChanged").asInt());
        assertEquals(surgery, assignmentsOf(alice).get(0).get("orgUnitId").asText());
        assertEquals(1, assignmentsOf(alice).size());
        assertEquals(List.of(chief), Api.json(api.get("/users/" + alice + "/managers")).findValuesAsText("userId"));
        assertEquals(0, assignmentsOf(boss).size());
    }

    @Test
    void readsTheAttributeNamesThatTheRealmSets() throws Exception {
        createRealm(Map.of("fuhito.sync.enabled", "true", "fuhito.sync.attribute.department", "busho",
                "fuhito.sync.attribute.title", "yakushoku"));
        String section = unit("総務課", null, null);
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"JOB_TITLE\", \"code\": \"kacho\", \"name\": \"課長\"}").statusCode());
        String user = user("kacho", Map.of("busho", "総務課", "yakushoku", "kacho", "department", "ignored"));

        JsonNode report = run("");

        assertEquals(1, report.get("assignmentsAdded").asInt());
        assertEquals(0, report.get("unmatched").size());
        assertEquals(section, assignmentsOf(user).get(0).get("orgUnitId").asText());
        assertEquals("kacho", assignmentsOf(user).get(0).get("jobTitle").asText());
    }

    @Test
    void aRealmThatDidNotTurnItOnGetsNothing() throws Exception {
        createRealm(Map.of());
        unit("内科", null, null);
        String user = user("alice", Map.of("department", "内科"));

        HttpResponse<String> response = api.post("/attribute-sync/run", "");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("sync.disabled", Api.json(response).get("error").asText());
        assertEquals(0, assignmentsOf(user).size());
        assertEquals(401, new Api(realm, null).post("/attribute-sync/run", "").statusCode());
    }

    @Test
    void anAttributeWithOnlySpacesCountsAsMissing() throws Exception {
        // A name of only spaces for an attribute means the default name.
        createRealm(Map.of("fuhito.sync.enabled", "true", "fuhito.sync.attribute.department", " "));
        unit("内科", "naika", null);
        String blank = user("blank", Map.of("department", " "));
        String padded = user("padded", Map.of("department", " naika ", "title", "  "));
        // Keycloak itself stores no value of only spaces. Another user store can hand one over.
        String spaces = user("spaces", Map.of("department", "naika"));
        KeycloakEnvironment.sql("update user_attribute set value = '   ' where user_id = '" + spaces
                + "' and name = 'department'");
        admin.realm(realm).clearUserCache();

        JsonNode report = run("");

        assertEquals(1, report.get("assignmentsAdded").asInt());
        assertEquals(0, assignmentsOf(blank).size());
        assertEquals(0, assignmentsOf(spaces).size());
        assertEquals(1, assignmentsOf(padded).size());
        assertEquals(0, report.get("unmatched").size());
    }
}

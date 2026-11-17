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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        TestRealm.createRealm(admin, representation);
        // Without this, the user profile of Keycloak drops attributes that it does not know.
        UPConfig profile = admin.realm(realm).users().userProfile().getConfiguration();
        profile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        admin.realm(realm).users().userProfile().update(profile);
        api = new Api(realm, admin.tokenManager().getAccessTokenString());
        TestRealm.legalEntity(api, "法人");
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
        if (!query.contains("dryRun=true")) {
            assertEquals("DONE", Api.json(api.get("/attribute-sync/last-run")).get("state").asText());
        }
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
        // A dry run wrote nothing, so it is not the last run.
        assertEquals(404, api.get("/attribute-sync/last-run").statusCode());

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
        JsonNode lineEntries = Api.json(api.get("/change-log?resourceType=FUHITO_REPORTING_LINE"));
        assertEquals(List.of("CREATE"), lineEntries.findValuesAsText("operation"));
        assertEquals(boss, lineEntries.get(0).get("representation").get("managerUserId").asText());

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

    private JsonNode historyOf(String userId) throws Exception {
        return Api.json(api.get("/users/" + userId + "/history"));
    }

    @Test
    void aUserWhoLeftGetsANewEmploymentWhenTheyComeBack() throws Exception {
        createRealm(Map.of("fuhito.sync.enabled", "true"));
        unit("内科", "naika", null);
        String alice = user("alice", Map.of("department", "naika"));
        String bob = user("bob", Map.of("department", "naika"));
        // Alice left in 2025. Her employment has the ID that the sync gave its employments before.
        HttpResponse<String> earlier = api.post("/employments", "{\"userId\": \"" + alice + "\", \"validFrom\": "
                + "\"2020-04-01\", \"validTo\": \"2025-04-01\", \"source\": \"scim\", \"externalId\": \"" + alice + "\"}");
        assertEquals(201, earlier.statusCode(), earlier.body());
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        assertEquals(2, run("").get("employmentsStarted").asInt());

        JsonNode ofAlice = historyOf(alice).get("employments");
        assertEquals(2, ofAlice.size());
        assertEquals("2025-04-01", ofAlice.get(0).get("validTo").asText());
        assertEquals(List.of("scim=" + alice), Api.externalIds(ofAlice.get(0)));
        assertEquals(today, ofAlice.get(1).get("validFrom").asText());
        assertEquals("scim", ofAlice.get(1).get("source").asText());
        assertEquals(List.of(), Api.externalIds(ofAlice.get(1)));
        assertEquals(1, assignmentsOf(alice).size());

        // The person of bob loses the link to the user. The user then has no person and no employment.
        String oldPerson = historyOf(bob).get("personId").asText();
        HttpResponse<String> unlinked = api.put("/people/" + oldPerson, "{\"name\": \"Bob\"}");
        assertEquals(200, unlinked.statusCode(), unlinked.body());

        JsonNode back = run("");

        assertEquals(1, back.get("employmentsStarted").asInt());
        assertEquals(1, back.get("assignmentsAdded").asInt());
        JsonNode ofBob = historyOf(bob);
        assertNotEquals(oldPerson, ofBob.get("personId").asText());
        assertEquals(1, ofBob.get("employments").size());
        assertEquals(1, assignmentsOf(bob).size());
        // The old person keeps the employment and the assignment, and the sync leaves them alone.
        JsonNode ofOldPerson = Api.json(api.get("/people/" + oldPerson + "/history"));
        assertEquals(1, ofOldPerson.get("employments").size());
        assertFalse(ofOldPerson.get("assignments").get(0).has("validTo"), ofOldPerson.toString());
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
        JsonNode run = Api.json(api.get("/attribute-sync/last-run"));
        assertEquals("FAILED", run.get("state").asText());
        assertEquals("sync.disabled", run.get("error").asText());
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

    @Test
    void theSyncNamesItsEmployerInARealmWithSeveralLegalEntities() throws Exception {
        createRealm(Map.of("fuhito.sync.enabled", "true", "fuhito.sync.employer", "y-service"));
        unit("内科", "naika", null);
        String alice = user("alice", Map.of("department", "naika"));

        // The code names no org unit yet.
        HttpResponse<String> unknown = api.post("/attribute-sync/run", "");
        assertEquals(409, unknown.statusCode(), unknown.body());
        assertEquals("sync.employer_unknown", Api.json(unknown).get("error").asText());

        HttpResponse<String> second = api.post("/org-units", "{\"name\": \"株式会社Yサービス\", \"code\": \"y-service\", "
                + "\"unitType\": \"legal-entity\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, second.statusCode(), second.body());
        String employer = Api.json(second).get("id").asText();

        assertEquals(1, run("").get("employmentsStarted").asInt());
        assertEquals(employer, Api.json(api.get("/users/" + alice + "/history")).get("employments").get(0).get("employerId").asText());
    }

    private List<String> changeLog(String resourceType) throws Exception {
        return Api.json(api.get("/change-log?resourceType=" + resourceType)).findValuesAsText("resourceId");
    }

    @Test
    void aUserThatTheSyncCannotHandleStopsNoOtherUser() throws Exception {
        createRealm(Map.of("fuhito.sync.enabled", "true"));
        String employer = TestRealm.legalEntity(api, "株式会社Yサービス");
        unit("内科", "naika", null);
        String alice = user("alice", Map.of("department", "naika"));
        String bob = user("bob", Map.of("department", "naika"));
        String carol = user("carol", Map.of("department", "naika"));
        // Bob has no employment, and the realm has two legal entities. So the sync cannot tell his employer.
        for (String userId : List.of(alice, carol)) {
            HttpResponse<String> employment = api.post("/employments", "{\"userId\": \"" + userId
                    + "\", \"validFrom\": \"2020-04-01\", \"employerId\": \"" + employer + "\"}");
            assertEquals(201, employment.statusCode(), employment.body());
        }
        List<String> people = changeLog("FUHITO_PERSON");

        JsonNode report = run("");

        JsonNode failed = report.get("failed");
        assertEquals(1, failed.size(), report.toString());
        assertEquals(bob, failed.get(0).get("userId").asText());
        assertEquals("employment.employer_required", failed.get(0).get("error").asText());
        assertEquals(0, report.get("employmentsStarted").asInt());
        assertEquals(2, report.get("assignmentsAdded").asInt());
        assertEquals(1, assignmentsOf(alice).size());
        assertEquals(1, assignmentsOf(carol).size());
        assertEquals(0, assignmentsOf(bob).size());
        // The person that the sync made for bob went away with his transaction.
        assertEquals(people, changeLog("FUHITO_PERSON"));
        assertEquals(2, changeLog("FUHITO_ASSIGNMENT").size());
        assertEquals(1, Api.json(api.get("/attribute-sync/last-run")).get("report").get("failed").asInt());
    }
}

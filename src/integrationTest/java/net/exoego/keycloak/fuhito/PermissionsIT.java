package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller may do with each role of the extension.
 */
class PermissionsIT {

    private static final String REALM = "master";
    private static final String PASSWORD = "permissions-it-password";

    private static final List<Keycloak> CLIENTS = new ArrayList<>();
    private static Api structureReader;
    private static Api reader;
    private static String unit;

    @BeforeAll
    static void createAdministrators() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var master = admin.realm(REALM).toRepresentation();
        TestRealm.withTimeZone(master);
        admin.realm(REALM).update(master);
        Api all = new Api(REALM, admin.tokenManager().getAccessTokenString());
        var created = all.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Permissions\"}}, \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        unit = Api.json(created).get("id").asText();

        structureReader = administrator(admin, REALM, "master-realm", "structure-reader", "fuhito-view-structure");
        reader = administrator(admin, REALM, "master-realm", "people-reader", "fuhito-view-people");
    }

    static Api administrator(Keycloak admin, String realm, String adminClient, String username,
            String... roles) {
        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setEmailVerified(true);
        user.setFirstName("Permissions");
        user.setLastName(String.join(" ", roles));
        user.setEnabled(true);
        user.setCredentials(List.of(credential));
        String userId;
        try (Response response = admin.realm(realm).users().create(user)) {
            assertEquals(201, response.getStatus());
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        String adminClientId = admin.realm(realm).clients().findByClientId(adminClient).get(0).getId();
        var granted = Arrays.stream(roles)
                .map(role -> admin.realm(realm).clients().get(adminClientId).roles().get(role).toRepresentation())
                .toList();
        admin.realm(realm).users().get(userId).roles().clientLevel(adminClientId).add(granted);

        Keycloak client = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(realm)
                .clientId("admin-cli")
                .username(username)
                .password(PASSWORD)
                .build();
        // Closing the client logs out, and that makes its token invalid. So it stays open until the tests end.
        CLIENTS.add(client);
        return new Api(realm, client.tokenManager().getAccessTokenString());
    }

    @AfterAll
    static void logOut() {
        CLIENTS.forEach(Keycloak::close);
    }

    private static List<String> structureReads() {
        return List.of("", "/org-units", "/org-units?legalEntity=true", "/org-units/" + unit, "/org-units/" + unit + "/children",
                "/org-units/" + unit + "/subtree", "/org-units/" + unit + "/path", "/org-units/" + unit + "/history",
                "/hierarchies", "/ref-items?kind=UNIT_TYPE", "/org-units/count", "/org-units/" + unit + "/children/count",
                "/org-units/" + unit + "/subtree/count", "/hierarchies/count", "/ref-items/count?kind=UNIT_TYPE");
    }

    // A count needs the same permission as its list.
    private static List<String> peopleReads() {
        return List.of("/org-units/" + unit + "/members", "/org-units/" + unit + "/role-holders", "/unit-roles",
                "/people", "/change-log", "/change-orders", "/effective-changes", "/org-units/" + unit + "/members/count",
                "/org-units/" + unit + "/role-holders/count", "/unit-roles/count", "/people/count", "/change-log/count",
                "/change-orders/count", "/effective-changes/count", "/employments/count", "/assignments/count",
                "/qualifications/count");
    }

    @Test
    void aCallerWhoMayViewTheStructureReadsTheStructureOnly() throws Exception {
        for (String path : structureReads()) {
            assertEquals(200, structureReader.get(path).statusCode(), path);
        }
        for (String path : peopleReads()) {
            assertEquals(403, structureReader.get(path).statusCode(), path);
        }
        assertEquals(403, structureReader.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2020-04-01\"}").statusCode());
    }

    @Test
    void aCallerWhoMayViewPeopleReadsEverythingAndWritesNothing() throws Exception {
        for (String path : structureReads()) {
            assertEquals(200, reader.get(path).statusCode(), path);
        }
        for (String path : peopleReads()) {
            assertEquals(200, reader.get(path).statusCode(), path);
        }
        assertEquals(403, reader.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2020-04-01\"}").statusCode());
    }

    @Test
    void theRolesOfKeycloakForUsersGiveNoAccess() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        for (String role : List.of("query-users", "view-users", "manage-users")) {
            Api caller = administrator(admin, REALM, "master-realm", "keycloak-" + role, role);
            assertEquals(403, caller.get("/org-units").statusCode(), role);
            assertEquals(403, caller.get("/people").statusCode(), role);
            assertEquals(403, caller.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2020-04-01\"}")
                    .statusCode(), role);
        }
    }

    @Test
    void aRealmHasTheRolesAndItsAdministratorsHoldThem() throws Exception {
        TestRealm realm = TestRealm.create("roles");
        try {
            Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
            String management = admin.realm(realm.name).clients().findByClientId("realm-management").get(0).getId();
            String masterClient = admin.realm(REALM).clients().findByClientId(realm.name + "-realm").get(0).getId();
            for (var roles : List.of(admin.realm(realm.name).clients().get(management).roles(),
                    admin.realm(REALM).clients().get(masterClient).roles())) {
                assertEquals(Set.of("fuhito-view-people"), names(roles.get("fuhito-manage").getRoleComposites()));
                assertEquals(Set.of("fuhito-view-structure"),
                        names(roles.get("fuhito-view-people").getRoleComposites()));
            }
            assertTrue(names(admin.realm(realm.name).clients().get(management).roles().get("realm-admin")
                    .getRoleComposites()).contains("fuhito-manage"));
            assertTrue(admin.realm(REALM).roles().get("admin").getRoleComposites().stream()
                    .anyMatch(role -> role.getName().equals("fuhito-manage")
                            && masterClient.equals(role.getContainerId())));

            // An administrator of the realm itself may do everything.
            Api realmAdmin = administrator(admin, realm.name, "realm-management", "realm-admin", "realm-admin");
            assertEquals(200, realmAdmin.get("/people").statusCode());
            HttpResponse<String> created = realmAdmin.post("/org-units",
                    "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2020-04-01\"}");
            assertEquals(201, created.statusCode(), created.body());
        } finally {
            realm.remove();
        }
    }

    private static Set<String> names(Set<RoleRepresentation> roles) {
        return roles.stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
    }

    @Test
    void aRealmLimitsHowFarAheadACallerWhoMayViewTheStructureReadsIt() throws Exception {
        TestRealm realm = TestRealm.create("structure-limit", Map.of("fuhito.structure.daysAhead", "30"));
        try {
            Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
            Api limited = administrator(admin, realm.name, "realm-management", "limited", "fuhito-view-structure");
            Api everything = administrator(admin, realm.name, "realm-management", "everything",
                    "fuhito-view-people");
            LocalDate lastDay = LocalDate.now(ZoneOffset.UTC).plusDays(30);
            String before = unit(realm.api, "Before");
            String unit = unit(realm.api, "Now");
            String after = unit(realm.api, "After");
            succeed(realm.api, lastDay, before, unit);
            succeed(realm.api, lastDay.plusDays(1), unit, after);

            assertEquals(200, limited.get("/org-units/" + unit + "?asOf=" + lastDay).statusCode());
            var tooFar = limited.get("/org-units/" + unit + "?asOf=" + lastDay.plusDays(1));
            assertEquals(403, tooFar.statusCode());
            assertEquals("structure.too_far_ahead", Api.json(tooFar).get("error").asText());
            assertEquals(403, limited.get("/org-units?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(403, limited.get("/org-units/count?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(403, limited.get("/org-units/" + unit + "/subtree/count?asOf=" + lastDay.plusDays(1))
                    .statusCode());
            assertEquals(403, limited.get("/org-units/" + unit + "/history").statusCode());
            assertEquals(200, everything.get("/org-units/" + after + "?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(200, everything.get("/org-units/" + unit + "/history").statusCode());

            // A rename of a job title after the limit is a later change too.
            String title = realm.refItem("JOB_TITLE", "chief", "主任");
            assertEquals(200, realm.api.putWithVersion("/ref-items/" + title, "{\"name\": \"主任補佐\", "
                    + "\"validFrom\": \"" + lastDay.plusDays(1) + "\"}").statusCode());
            assertEquals(403, limited.get("/ref-items/" + title + "?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(403, limited.get("/ref-items?kind=JOB_TITLE&asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(403, limited.get("/ref-items/" + title + "/history").statusCode());
            assertFalse(Api.json(limited.get("/ref-items/" + title)).has("validTo"));
            assertFalse(Api.json(limited.get("/ref-items?kind=JOB_TITLE")).get(0).has("validTo"));
            assertEquals(lastDay.plusDays(1).toString(),
                    Api.json(everything.get("/ref-items/" + title)).get("validTo").asText());
            assertEquals(200, everything.get("/ref-items/" + title + "/history").statusCode());

            JsonNode seen = Api.json(limited.get("/org-units/" + unit + "/succession"));
            assertEquals(List.of(before), seen.get("predecessors").findValuesAsText("orgUnitId"));
            assertEquals(0, seen.get("successors").size());
            JsonNode all = Api.json(everything.get("/org-units/" + unit + "/succession"));
            assertEquals(List.of(after), all.get("successors").findValuesAsText("orgUnitId"));

            // The end of the unit is a later change.
            var limitedRead = limited.get("/org-units/" + unit + "?asOf=" + lastDay);
            assertFalse(Api.json(limitedRead).has("validTo"), limitedRead.body());
            var fullRead = everything.get("/org-units/" + unit + "?asOf=" + lastDay);
            assertEquals(lastDay.plusDays(1).toString(), Api.json(fullRead).get("validTo").asText());
            assertFalse(Api.json(limited.get("/org-units/" + unit + "/path?asOf=" + lastDay)).get(0).has("validTo"));

            // A unit that starts after the limit is unknown to the limited caller, also by an ID of another system.
            HttpResponse<String> later = realm.api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Later\"}}, \"validFrom\": \""
                    + lastDay.plusDays(5) + "\", \"externalId\": \"L-1\", \"source\": \"hr\"}");
            assertEquals(201, later.statusCode(), later.body());
            String lookup = "/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=L-1";
            assertEquals(404, limited.get(lookup).statusCode());
            assertEquals(200, everything.get(lookup).statusCode());

            var representation = admin.realm(realm.name).toRepresentation();
            representation.getAttributes().put("fuhito.structure.daysAhead", "soon");
            admin.realm(realm.name).update(representation);
            var invalid = limited.get("/org-units/" + unit);
            assertEquals(409, invalid.statusCode());
            assertEquals("structure.days_ahead_invalid", Api.json(invalid).get("error").asText());
            assertEquals(200, everything.get("/org-units/" + unit).statusCode());
        } finally {
            realm.remove();
        }
    }

    @Test
    void aLinkToAnOrganizationAsksForThePermissionToManageIt() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var representation = new RealmRepresentation();
        representation.setRealm("organization-permission-" + UUID.randomUUID());
        representation.setEnabled(true);
        representation.setOrganizationsEnabled(true);
        TestRealm.withTimeZone(representation);
        TestRealm.createRealm(admin, representation);
        String realm = representation.getRealm();
        try {
            Api all = new Api(realm, admin.tokenManager().getAccessTokenString());
            String organization = OrganizationLinkIT.createOrganization(admin, realm, "permission-org");
            String unit = unit(all, "Linked");
            Api userAdmin = administrator(admin, realm, "realm-management", "user-admin", "fuhito-manage");
            String link = "/org-units/" + unit + "/organization";
            String body = "{\"organizationId\": \"" + organization + "\"}";

            assertEquals(403, userAdmin.putWithVersion(link, body).statusCode());
            assertEquals(200, all.putWithVersion(link, body).statusCode());
            assertEquals(403, userAdmin.deleteWithVersion(link).statusCode());
            // The other writes need the role of the extension only.
            assertEquals(201, userAdmin.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Other\"}}, \"validFrom\": \"2020-04-01\"}")
                    .statusCode());
            assertEquals(200, all.deleteWithVersion(link).statusCode());
        } finally {
            admin.realm(realm).remove();
        }
    }

    @Test
    void aRebuildAsksForThePermissionToChangeWhatItWritesInKeycloak() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var representation = new RealmRepresentation();
        representation.setRealm("rebuild-permission-" + UUID.randomUUID());
        representation.setEnabled(true);
        representation.setOrganizationsEnabled(true);
        representation.setAttributes(Map.of("fuhito.projection.enabled", "true"));
        TestRealm.withTimeZone(representation);
        TestRealm.createRealm(admin, representation);
        String realm = representation.getRealm();
        try {
            Api all = new Api(realm, admin.tokenManager().getAccessTokenString());
            String organization = OrganizationLinkIT.createOrganization(admin, realm, "rebuild-org");
            String unit = unit(all, "Linked");
            assertEquals(200, all.putWithVersion("/org-units/" + unit + "/organization",
                    "{\"organizationId\": \"" + organization + "\"}").statusCode());
            Api writer = administrator(admin, realm, "realm-management", "writer", "fuhito-manage");
            Api organizationAdmin = administrator(admin, realm, "realm-management", "organization-admin",
                    "fuhito-manage", "manage-organizations");

            assertEquals(403, writer.post("/projection/rebuild", "").statusCode());
            HttpResponse<String> rebuilt = organizationAdmin.post("/projection/rebuild", "");
            assertEquals(200, rebuilt.statusCode(), rebuilt.body());

            // Realm groups are groups of Keycloak too.
            var settings = admin.realm(realm).toRepresentation();
            settings.getAttributes().put("fuhito.projection.realmGroup", "/org");
            admin.realm(realm).update(settings);
            var org = new GroupRepresentation();
            org.setName("org");
            admin.realm(realm).groups().add(org).close();
            assertEquals(403, organizationAdmin.post("/projection/rebuild", "").statusCode());
            Api groupAdmin = administrator(admin, realm, "realm-management", "group-admin", "fuhito-manage",
                    "manage-organizations", "manage-users");
            HttpResponse<String> mirrored = groupAdmin.post("/projection/rebuild", "");
            assertEquals(200, mirrored.statusCode(), mirrored.body());
        } finally {
            admin.realm(realm).remove();
        }
    }

    @Test
    void clearingTheNameInKeycloakAsksForThePermissionToManageTheUser() throws Exception {
        TestRealm realm = TestRealm.create("clear-name");
        try {
            Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
            String user = realm.user("taro", "Taro", "Yamada");
            Api writer = administrator(admin, realm.name, "realm-management", "writer", "fuhito-manage");
            Api userAdmin = administrator(admin, realm.name, "realm-management", "user-admin", "fuhito-manage",
                    "manage-users");

            assertEquals(403, writer.post("/users/" + user + "/mask?clearKeycloakName=true", "").statusCode());
            assertEquals("Taro", admin.realm(realm.name).users().get(user).toRepresentation().getFirstName());
            HttpResponse<String> masked = writer.post("/users/" + user + "/mask", "");
            assertEquals(200, masked.statusCode(), masked.body());
            String person = Api.json(masked).get("personId").asText();
            assertEquals(403, writer.postWithVersion("/people/" + person + "/mask?clearKeycloakName=true", "").statusCode());

            HttpResponse<String> cleared = userAdmin.postWithVersion("/people/" + person + "/mask?clearKeycloakName=true", "");
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(Api.json(cleared).get("keycloakNameCleared").asBoolean());
        } finally {
            realm.remove();
        }
    }

    private static String unit(Api api, String name) throws Exception {
        var created = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}, \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        return Api.json(created).get("id").asText();
    }

    private static void succeed(Api api, LocalDate date, String id, String successorId) throws Exception {
        var done = api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"" + date + "\", \"steps\": [{\"op\": "
                + "\"succeed\", \"id\": \"" + id + "\", \"successorIds\": [\"" + successorId + "\"]}]}");
        assertEquals(200, done.statusCode(), done.body());
    }
}

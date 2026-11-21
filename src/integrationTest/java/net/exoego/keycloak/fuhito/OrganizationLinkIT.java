package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrganizationLinkIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String createRealm(Keycloak admin, boolean organizations) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-link-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(organizations);
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(admin, realm);
        return realm.getRealm();
    }

    static String createOrganization(Keycloak admin, String realm, String alias) {
        var organization = new OrganizationRepresentation();
        organization.setName(alias);
        organization.setAlias(alias);
        organization.addDomain(new OrganizationDomainRepresentation(alias + ".example.com"));
        try (Response response = admin.realm(realm).organizations().create(organization)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String unit(Api api, String name) throws Exception {
        return unit(api, name, "2026-04-01");
    }

    private static String unit(Api api, String name, String validFrom) throws Exception {
        HttpResponse<String> response = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}, \"validFrom\": \"" + validFrom + "\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    // The ID of an organization is unique in all realms.
    private static String idOfLength(int length) {
        return (UUID.randomUUID() + "-" + "o".repeat(length)).substring(0, length);
    }

    // The admin API gives a new organization a UUID, but a realm import keeps the ID that its JSON gives.
    private static String createRealmWithOrganizations(Keycloak admin, Map<String, String> idsByAlias) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-long-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        realm.setAttributes(Map.of("fuhito.projection.enabled", "true"));
        realm.setOrganizations(idsByAlias.entrySet().stream().map(entry -> {
            var organization = new OrganizationRepresentation();
            organization.setId(entry.getValue());
            organization.setName(entry.getKey());
            organization.setAlias(entry.getKey());
            organization.setEnabled(true);
            organization.addDomain(new OrganizationDomainRepresentation(entry.getKey() + ".example.com"));
            return organization;
        }).toList());
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(admin, realm);
        return realm.getRealm();
    }

    private static String organizationOf(Api api, String unit) throws Exception {
        return Api.json(api.get("/org-units/" + unit + "?asOf=2026-05-01")).path("keycloakOrganizationId").asText();
    }

    @Test
    void linksTwoHospitalsToTwoOrganizations() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, true);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String xOrganization = createOrganization(admin, realm, "x-hospital");
        String yOrganization = createOrganization(admin, realm, "y-hospital");
        String xHospital = unit(api, "X病院");
        String yHospital = unit(api, "Y病院");

        HttpResponse<String> linked = api.putWithVersion("/org-units/" + xHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}");
        assertEquals(200, linked.statusCode(), linked.body());
        assertEquals(xHospital, Api.json(linked).get("orgUnitId").asText());
        assertEquals(xOrganization, Api.json(linked).get("organizationId").asText());
        assertEquals(200, api.putWithVersion("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + yOrganization + "\"}").statusCode());

        JsonNode roots = Api.json(api.get("/org-units?roots=true&asOf=2026-04-01"));
        assertEquals(List.of(xOrganization, yOrganization).stream().sorted().toList(),
                roots.findValuesAsText("keycloakOrganizationId").stream().sorted().toList());
        assertEquals(xOrganization, Api.json(api.get("/org-units/" + xHospital + "?asOf=2026-04-01")).get("keycloakOrganizationId").asText());

        // One organization has one org unit. An unknown organization is rejected.
        assertEquals("organization.linked", errorOf(409, api.putWithVersion("/org-units/" + yHospital + "/organization",
                "{\"organizationId\": \"" + xOrganization + "\"}")));
        assertEquals("organization.unknown", errorOf(400, api.putWithVersion("/org-units/" + yHospital + "/organization",
                "{\"organizationId\": \"no-such-organization\"}")));
        assertEquals("invalid.required", errorOf(400, api.putWithVersion("/org-units/" + yHospital + "/organization", "{}")));
        assertEquals("org_unit.not_found", errorOf(404, api.putWithVersion("/org-units/nope/organization",
                "{\"organizationId\": \"" + xOrganization + "\"}")));

        HttpResponse<String> unlinked = api.deleteWithVersion("/org-units/" + xHospital + "/organization");
        assertEquals(200, unlinked.statusCode(), unlinked.body());
        assertFalse(Api.json(unlinked).has("organizationId"));
        assertFalse(Api.json(api.get("/org-units/" + xHospital + "?asOf=2026-04-01")).has("keycloakOrganizationId"));
        assertEquals(200, api.putWithVersion("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}").statusCode());
    }

    @Test
    void aUnitLinksToAnOrganizationWithAnIdOf40Characters() throws Exception {
        linksToOrganizationsWithIdsOf(40);
    }

    // Keycloak keeps the ID of an organization in VARCHAR(255).
    @Test
    void aUnitLinksToAnOrganizationWithAnIdOf255Characters() throws Exception {
        linksToOrganizationsWithIdsOf(255);
    }

    private static void linksToOrganizationsWithIdsOf(int length) throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String xOrganization = idOfLength(length);
        String yOrganization = idOfLength(length);
        String realm = createRealmWithOrganizations(admin, Map.of("x-hospital", xOrganization,
                "y-hospital", yOrganization));
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        TestRealm.legalEntity(api, "法人");
        String xHospital = unit(api, "X病院", "2020-04-01");
        String yHospital = unit(api, "Y病院", "2020-04-01");
        var nurse = new UserRepresentation();
        nurse.setUsername("nurse");
        nurse.setEnabled(true);
        try (Response response = admin.realm(realm).users().create(nurse)) {
            HttpResponse<String> staff = api.post("/staff", "{\"userId\": \""
                    + CreatedResponseUtil.getCreatedId(response) + "\", \"orgUnitId\": \"" + xHospital
                    + "\", \"validFrom\": \"2020-04-01\"}");
            assertEquals(201, staff.statusCode(), staff.body());
        }

        HttpResponse<String> linked = api.putWithVersion("/org-units/" + xHospital + "/organization",
                "{\"organizationId\": \"" + xOrganization + "\"}");
        assertEquals(200, linked.statusCode(), linked.body());
        HttpResponse<String> loaded = api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01",
                "text/csv; charset=UTF-8", ("id,organizationAlias\r\n" + yHospital + ",y-hospital\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(200, loaded.statusCode(), loaded.body());
        assertEquals(0, Api.json(loaded).get("errors").size(), loaded.body());
        assertEquals(xOrganization, organizationOf(api, xHospital));
        assertEquals(yOrganization, organizationOf(api, yHospital));
        assertEquals(200, api.post("/projection/rebuild", "").statusCode());
        assertEquals("1", KeycloakEnvironment.sql("select count(*) from fuhito_projected_member where kc_org_id = '"
                + xOrganization + "'"));

        JsonNode export = RealmExportIT.export(api);
        assertEquals(List.of("x-hospital", "y-hospital"), export.get("orgUnits").findValuesAsText("organizationAlias")
                .stream().sorted().toList());
        assertEquals(200, api.deleteWithVersion("/org-units/" + xHospital + "/organization").statusCode());
        assertEquals(200, api.deleteWithVersion("/org-units/" + yHospital + "/organization").statusCode());
        HttpResponse<String> restored = api.post("/restore?replace=true", JSON.writeValueAsString(export));
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(xOrganization, organizationOf(api, xHospital));
        assertEquals(yOrganization, organizationOf(api, yHospital));
    }

    @Test
    void removingAnOrganizationRemovesItsLink() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, true);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String organization = createOrganization(admin, realm, "short-lived");
        String hospital = unit(api, "X病院");
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization", "{\"organizationId\": \"" + organization + "\"}").statusCode());

        admin.realm(realm).organizations().get(organization).delete().close();

        JsonNode unit = Api.json(api.get("/org-units/" + hospital + "?asOf=2026-04-01"));
        assertEquals("X病院", unit.get("name").asText());
        assertFalse(unit.has("keycloakOrganizationId"));
    }

    // The removal counts up the version of the unit in the database, which does not let it wrap around.
    @Test
    void removingAnOrganizationFailsWhenItsUnitHasTheLargestVersion() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, true);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String organization = createOrganization(admin, realm, "largest-version");
        String hospital = unit(api, "X病院");
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization", "{\"organizationId\": \"" + organization + "\"}").statusCode());
        KeycloakEnvironment.sql("update fuhito_org_unit set version = " + Long.MAX_VALUE + " where id = '"
                + hospital + "'");

        try (Response removed = admin.realm(realm).organizations().get(organization).delete()) {
            assertEquals(500, removed.getStatus());
        }

        JsonNode unit = Api.json(api.get("/org-units/" + hospital + "?asOf=2026-04-01"));
        assertEquals(List.of(organization, Long.toString(Long.MAX_VALUE)),
                List.of(unit.path("keycloakOrganizationId").asText(), unit.get("version").asText()));
        assertEquals(organization, admin.realm(realm).organizations().get(organization).toRepresentation().getId());
    }

    @Test
    void aRealmWithoutOrganizationsWorksAndRejectsALink() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, false);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String hospital = unit(api, "X病院");

        assertEquals("organization.unknown", errorOf(400, api.putWithVersion("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"anything\"}")));
        assertEquals(200, api.deleteWithVersion("/org-units/" + hospital + "/organization").statusCode());
        assertEquals(401, new Api(realm, null).putWithVersion("/org-units/" + hospital + "/organization", "{}").statusCode());
    }
}

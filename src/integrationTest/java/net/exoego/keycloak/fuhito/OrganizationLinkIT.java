package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrganizationLinkIT {

    private static String createRealm(Keycloak admin, boolean organizations) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-link-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(organizations);
        admin.realms().create(realm);
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
        HttpResponse<String> response = api.post("/org-units", "{\"name\": \"" + name + "\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
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

        HttpResponse<String> linked = api.put("/org-units/" + xHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}");
        assertEquals(200, linked.statusCode(), linked.body());
        assertEquals(xHospital, Api.json(linked).get("orgUnitId").asText());
        assertEquals(xOrganization, Api.json(linked).get("organizationId").asText());
        assertEquals(200, api.put("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + yOrganization + "\"}").statusCode());

        JsonNode roots = Api.json(api.get("/org-units?roots=true&asOf=2026-04-01"));
        assertEquals(List.of(xOrganization, yOrganization).stream().sorted().toList(),
                roots.findValuesAsText("keycloakOrganizationId").stream().sorted().toList());
        assertEquals(xOrganization, Api.json(api.get("/org-units/" + xHospital + "?asOf=2026-04-01")).get("keycloakOrganizationId").asText());

        // One organization has one org unit. An unknown organization is rejected.
        assertEquals("organization.linked", errorOf(409, api.put("/org-units/" + yHospital + "/organization",
                "{\"organizationId\": \"" + xOrganization + "\"}")));
        assertEquals("organization.unknown", errorOf(400, api.put("/org-units/" + yHospital + "/organization",
                "{\"organizationId\": \"no-such-organization\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/org-units/" + yHospital + "/organization", "{}")));
        assertEquals("org_unit.not_found", errorOf(404, api.put("/org-units/nope/organization",
                "{\"organizationId\": \"" + xOrganization + "\"}")));

        HttpResponse<String> unlinked = api.delete("/org-units/" + xHospital + "/organization");
        assertEquals(200, unlinked.statusCode(), unlinked.body());
        assertFalse(Api.json(unlinked).has("organizationId"));
        assertFalse(Api.json(api.get("/org-units/" + xHospital + "?asOf=2026-04-01")).has("keycloakOrganizationId"));
        assertEquals(200, api.put("/org-units/" + yHospital + "/organization", "{\"organizationId\": \"" + xOrganization + "\"}").statusCode());
    }

    @Test
    void removingAnOrganizationRemovesItsLink() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, true);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String organization = createOrganization(admin, realm, "short-lived");
        String hospital = unit(api, "X病院");
        assertEquals(200, api.put("/org-units/" + hospital + "/organization", "{\"organizationId\": \"" + organization + "\"}").statusCode());

        admin.realm(realm).organizations().get(organization).delete().close();

        JsonNode unit = Api.json(api.get("/org-units/" + hospital + "?asOf=2026-04-01"));
        assertEquals("X病院", unit.get("name").asText());
        assertFalse(unit.has("keycloakOrganizationId"));
    }

    @Test
    void aRealmWithoutOrganizationsWorksAndRejectsALink() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String realm = createRealm(admin, false);
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        String hospital = unit(api, "X病院");

        assertEquals("organization.unknown", errorOf(400, api.put("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"anything\"}")));
        assertEquals(200, api.delete("/org-units/" + hospital + "/organization").statusCode());
        assertEquals(401, new Api(realm, null).put("/org-units/" + hospital + "/organization", "{}").statusCode());
    }
}

package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrgUnitsIT {

    private static Api api;
    private static Api otherRealmApi;

    @BeforeAll
    static void createRealms() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String token = admin.tokenManager().getAccessTokenString();
        api = new Api(createRealm(admin), token);
        otherRealmApi = new Api(createRealm(admin), token);
        assertEquals(201, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"hospital\", \"name\": \"病院\"}").statusCode());
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-units-" + UUID.randomUUID());
        realm.setEnabled(true);
        admin.realms().create(realm);
        return realm.getRealm();
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void createsARootWithOnlyANameAndADate() throws Exception {
        JsonNode unit = created(api.post("/org-units", "{\"name\": \"ホゲホゲ診療所\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals(36, unit.get("id").asText().length());
        assertEquals("ホゲホゲ診療所", unit.get("name").asText());
        assertEquals("2026-04-01", unit.get("validFrom").asText());
        // Keycloak leaves a field with no value out of the response.
        assertFalse(unit.has("validTo"));
        assertFalse(unit.has("parentId"));
        assertFalse(unit.has("unitType"));
        assertEquals(0, unit.get("displayOrder").asInt());
    }

    @Test
    void createsAHospitalWithADepartmentBelowIt() throws Exception {
        JsonNode hospital = created(api.post("/org-units", """
                {"code": "x-hospital", "name": "X病院", "names": {"en": "X Hospital"}, "shortName": "X",
                 "shortNames": {"en": "X"}, "phoneticName": "えっくすびょういん", "unitType": "hospital",
                 "validFrom": "2026-04-01", "displayOrder": 1, "attributes": {"beds": 120},
                 "externalId": "H-1", "source": "hr"}
                """));
        String hospitalId = hospital.get("id").asText();
        JsonNode department = created(api.post("/org-units",
                "{\"name\": \"内科\", \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("hospital", hospital.get("unitType").asText());
        assertEquals("X Hospital", hospital.get("names").get("en").asText());
        assertEquals("えっくすびょういん", hospital.get("phoneticName").asText());
        assertEquals(120, hospital.get("attributes").get("beds").asInt());
        assertEquals("H-1", hospital.get("externalId").asText());
        assertEquals(hospitalId, department.get("parentId").asText());

        assertEquals(hospital, Api.json(api.get("/org-units/" + hospitalId + "?asOf=2026-04-01")));
        assertEquals(department, Api.json(api.get("/org-units/" + department.get("id").asText() + "?asOf=2030-01-01")));
    }

    @Test
    void readsAUnitOnlyFromItsFirstDate() throws Exception {
        String future = LocalDate.now().plusYears(1).toString();
        String id = created(api.post("/org-units", "{\"name\": \"Next year\", \"validFrom\": \"" + future + "\"}"))
                .get("id").asText();

        assertEquals(200, api.get("/org-units/" + id + "?asOf=" + future).statusCode());
        assertEquals("org_unit.not_valid_on", errorOf(404, api.get("/org-units/" + id)));
        assertEquals("org_unit.not_found", errorOf(404, api.get("/org-units/no-such-id")));
        assertEquals("invalid.date", errorOf(400, api.get("/org-units/" + id + "?asOf=2026-13-01")));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String hospitalId = created(api.post("/org-units", "{\"name\": \"Y病院\", \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();

        assertEquals("invalid.required", errorOf(400, api.post("/org-units", "{\"name\": \"x\"}")));
        assertEquals("invalid.name", errorOf(400, api.post("/org-units", "{\"name\": \" \", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"x\", \"unitType\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_not_valid", errorOf(409, api.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-03-31\"}")));
        assertEquals("org_unit.code_taken", errorOf(409, api.post("/org-units",
                "{\"name\": \"x\", \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void aUnitTypeInUseCannotBeDeleted() throws Exception {
        String typeId = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"in-use\", \"name\": \"In use\"}")).get("id").asText();
        created(api.post("/org-units", "{\"name\": \"x\", \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + typeId)));
        assertEquals(200, api.put("/ref-items/" + typeId, "{\"name\": \"In use\", \"active\": false}").statusCode());
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"y\", \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void oneRealmDoesNotSeeTheUnitsOfAnother() throws Exception {
        String body = "{\"name\": \"Z病院\", \"code\": \"z-hospital\", \"validFrom\": \"2026-04-01\"}";
        String id = created(api.post("/org-units", body)).get("id").asText();

        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.get("/org-units/" + id + "?asOf=2026-04-01")));
        assertEquals("org_unit.parent_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"" + id + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"name\": \"x\", \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}")));
        created(otherRealmApi.post("/org-units", body));
    }

    @Test
    void eachRealmGetsItsOwnDefaultHierarchy() throws Exception {
        String parentId = created(api.post("/org-units", "{\"name\": \"P\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(api.post("/org-units", "{\"name\": \"C\", \"parentId\": \"" + parentId + "\", \"validFrom\": \"2026-04-01\"}"));

        JsonNode hierarchies = Api.json(api.get("/hierarchies"));
        assertEquals(1, hierarchies.size());
        assertEquals("default", hierarchies.get(0).get("code").asText());
    }

    @Test
    void rejectsARequestWithoutAToken() throws Exception {
        var anonymous = new Api("master", null);
        assertEquals(401, anonymous.post("/org-units", "{}").statusCode());
        assertEquals(401, anonymous.get("/org-units/x").statusCode());
    }
}

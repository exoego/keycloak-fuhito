package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * One corporation with two hospitals, and a reorganization on 2027-04-01.
 */
class OrgUnitTreeIT {

    private static Api api;
    private static Api emptyRealmApi;
    private static String xHospital;
    private static String yHospital;
    private static String nursing;
    private static String ward1;
    private static String surgery;

    @BeforeAll
    static void createTheOrganization() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String token = admin.tokenManager().getAccessTokenString();
        api = new Api(createRealm(admin), token);
        emptyRealmApi = new Api(createRealm(admin), token);
        assertEquals(201, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"hospital\", \"name\": \"病院\"}").statusCode());

        xHospital = create("X病院", "えっくすびょういん", null, 1, "hospital");
        yHospital = create("Y病院", "わいびょういん", null, 2, "hospital");
        nursing = create("看護部", "かんごぶ", xHospital, 2, null);
        create("診療部", "しんりょうぶ", xHospital, 1, null);
        ward1 = create("1病棟", "いちびょうとう", nursing, 0, null);
        create("2病棟", "にびょうとう", nursing, 0, null);
        surgery = create("外科", "げか", yHospital, 0, null);

        // On 2027-04-01 ward 1 moves to the other hospital with a new name, and surgery ends.
        assertEquals(200, api.put("/org-units/" + ward1 + "/parent",
                "{\"parentId\": \"" + yHospital + "\", \"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + ward1,
                "{\"name\": \"東病棟\", \"phoneticName\": \"ひがしびょうとう\", \"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + surgery + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-tree-" + UUID.randomUUID());
        realm.setEnabled(true);
        TestRealm.withTimeZone(realm);
        admin.realms().create(realm);
        return realm.getRealm();
    }

    private static String create(String name, String phoneticName, String parentId, int displayOrder, String unitType)
            throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        String type = unitType == null ? "" : ", \"unitType\": \"" + unitType + "\"";
        HttpResponse<String> response = api.post("/org-units", "{\"name\": \"" + name + "\", \"phoneticName\": \""
                + phoneticName + "\", \"displayOrder\": " + displayOrder + parent + type
                + ", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static List<String> names(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("name");
    }

    @Test
    void listsTheWholeTreeOnTwoDates() throws Exception {
        assertEquals(List.of("X病院", "診療部", "看護部", "1病棟", "2病棟", "Y病院", "外科"),
                names(api.get("/org-units?asOf=2027-03-31")));
        assertEquals(List.of("X病院", "診療部", "看護部", "2病棟", "Y病院", "東病棟"),
                names(api.get("/org-units?asOf=2027-04-01")));
        assertEquals(List.of("看護部", "1病棟"), names(api.get("/org-units?asOf=2027-03-31&first=2&max=2")));
        assertEquals(List.of("Y病院"), names(api.get("/org-units?roots=true&asOf=2027-03-31&first=1")));
        assertEquals(List.of(), names(api.get("/org-units?asOf=2026-03-31")));
        assertEquals(List.of(), names(emptyRealmApi.get("/org-units?asOf=2027-04-01")));
    }

    @Test
    void listsRootsWithTheirUnitType() throws Exception {
        JsonNode roots = Api.json(api.get("/org-units?roots=true&asOf=2026-04-01"));

        assertEquals(List.of("X病院", "Y病院"), roots.findValuesAsText("name"));
        assertEquals(List.of("hospital", "hospital"), roots.findValuesAsText("unitType"));
        assertFalse(roots.get(0).has("parentId"));
    }

    @Test
    void listsChildrenSubtreeAndPath() throws Exception {
        assertEquals(List.of("診療部", "看護部"), names(api.get("/org-units/" + xHospital + "/children?asOf=2026-04-01")));
        assertEquals(List.of("看護部", "1病棟", "2病棟"), names(api.get("/org-units/" + nursing + "/subtree?asOf=2027-03-31")));
        assertEquals(List.of("看護部", "2病棟"), names(api.get("/org-units/" + nursing + "/subtree?asOf=2027-04-01")));
        assertEquals(List.of("看護部"), names(api.get("/org-units/" + xHospital + "/children?asOf=2026-04-01&first=1&max=1")));
        assertEquals(List.of("1病棟"), names(api.get("/org-units/" + nursing + "/subtree?asOf=2027-03-31&first=1&max=1")));
        assertEquals(List.of("X病院", "看護部", "1病棟"), names(api.get("/org-units/" + ward1 + "/path?asOf=2027-03-31")));
        assertEquals(List.of("Y病院", "東病棟"), names(api.get("/org-units/" + ward1 + "/path?asOf=2027-04-01")));
        assertEquals(nursing, Api.json(api.get("/org-units/" + nursing + "/subtree?asOf=2027-03-31")).get(1).get("parentId").asText());
    }

    @Test
    void readsTheHistoryOfAUnit() throws Exception {
        HttpResponse<String> response = api.get("/org-units/" + ward1 + "/history");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode history = Api.json(response);
        assertEquals(ward1, history.get("id").asText());
        assertEquals(List.of("1病棟", "東病棟"), history.get("versions").findValuesAsText("name"));
        assertEquals("2027-04-01", history.get("versions").get(0).get("validTo").asText());
        assertFalse(history.get("versions").get(1).has("validTo"));
        assertEquals(List.of(nursing, yHospital), history.get("parents").findValuesAsText("parentId"));
        assertEquals("2027-04-01", history.get("parents").get(0).get("validTo").asText());
        assertEquals("2027-04-01", history.get("parents").get(1).get("validFrom").asText());
    }

    @Test
    void rejectsReadsOfAUnitThatIsNotThere() throws Exception {
        for (String sub : List.of("children", "subtree", "path")) {
            assertEquals(404, api.get("/org-units/" + surgery + "/" + sub + "?asOf=2027-04-01").statusCode());
            assertEquals(404, api.get("/org-units/nope/" + sub).statusCode());
            assertEquals(404, emptyRealmApi.get("/org-units/" + xHospital + "/" + sub + "?asOf=2026-04-01").statusCode());
            assertEquals(400, api.get("/org-units/" + xHospital + "/" + sub + "?asOf=nope").statusCode());
        }
        assertEquals(404, api.get("/org-units/nope/history").statusCode());
        assertEquals(404, emptyRealmApi.get("/org-units/" + xHospital + "/history").statusCode());
        assertEquals(400, api.get("/org-units?asOf=nope").statusCode());
        assertEquals(401, new Api("master", null).get("/org-units").statusCode());
    }
}

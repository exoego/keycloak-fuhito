package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A hospital that groups its clinical departments under a new division on 2027-04-01.
 */
class ReorganizationIT {

    private static TestRealm realm;
    private static Api api;
    private static String hospital;
    private static String medicine;
    private static String surgery;
    private static String oldOffice;

    @BeforeAll
    static void createTheHospital() throws Exception {
        realm = TestRealm.create("reorganization");
        api = realm.api;
        hospital = unit("X病院", null, 0);
        medicine = unit("内科", hospital, 1);
        surgery = unit("外科", hospital, 2);
        oldOffice = unit("旧事務室", hospital, 3);
    }

    private static String unit(String name, String parentId, int displayOrder) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        HttpResponse<String> response = api.post("/org-units", "{\"name\": \"" + name + "\"" + parent
                + ", \"displayOrder\": " + displayOrder + ", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static List<String> names(String asOf) throws Exception {
        return Api.json(api.get("/org-units?asOf=" + asOf)).findValuesAsText("name");
    }

    @Test
    void appliesAReorganizationUnderAChangeOrder() throws Exception {
        String order = Api.json(api.post("/change-orders",
                "{\"title\": \"2027年度組織改編\", \"kind\": \"reorganization\", \"effectiveDate\": \"2027-04-01\"}")).get("id").asText();

        HttpResponse<String> response = api.post("/reorganizations?changeOrder=" + order, """
                {"effectiveDate": "2027-04-01", "steps": [
                  {"op": "change", "id": "%s", "name": "X総合病院"},
                  {"op": "create", "ref": "clinical", "name": "診療部", "parentId": "%s", "displayOrder": 1},
                  {"op": "move", "id": "%s", "parentRef": "clinical"},
                  {"op": "move", "id": "%s", "parentRef": "clinical"},
                  {"op": "end", "id": "%s"}
                ]}
                """.formatted(hospital, hospital, medicine, surgery, oldOffice));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode results = Api.json(response).get("results");
        assertEquals(List.of("change", "create", "move", "move", "end"), results.findValuesAsText("op"));
        String clinical = results.get(1).get("unit").get("id").asText();
        assertEquals(clinical, results.get(2).get("unit").get("parentId").asText());
        assertEquals("2027-04-01", results.get(4).get("unit").get("validTo").asText());

        assertEquals(List.of("X病院", "内科", "外科", "旧事務室"), names("2027-03-31"));
        assertEquals(List.of("X総合病院", "診療部", "内科", "外科"), names("2027-04-01"));
        assertEquals(List.of("X総合病院", "診療部", "内科"),
                Api.json(api.get("/org-units/" + medicine + "/path?asOf=2027-04-01")).findValuesAsText("name"));

        JsonNode changes = Api.json(api.get("/change-orders/" + order + "/changes"));
        assertEquals(List.of(hospital, clinical, medicine, surgery, oldOffice), changes.findValuesAsText("resourceId"));
        assertEquals(List.of("UPDATE", "CREATE", "UPDATE", "UPDATE", "UPDATE"), changes.findValuesAsText("operation"));
        assertEquals("fuhito/org-units/" + medicine + "/parent", changes.get(2).get("resourcePath").asText());
        assertEquals("fuhito/org-units/" + oldOffice + "/end", changes.get(4).get("resourcePath").asText());
    }

    @Test
    void aFailedStepWritesNothing() throws Exception {
        TestRealm scratch = TestRealm.create("reorganization-failed");
        String root = Api.json(scratch.api.post("/org-units", "{\"name\": \"Root\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String child = Api.json(scratch.api.post("/org-units", "{\"name\": \"Child\", \"parentId\": \"" + root + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String before = scratch.api.get("/org-units?asOf=2030-01-01").body();
        String rows = "select count(*) from fuhito_org_unit_version where realm_id = '" + scratch.id() + "'";
        String logRows = "select count(*) from fuhito_change_log where realm_id = '" + scratch.id() + "'";
        assertEquals("2", KeycloakEnvironment.sql(rows));
        assertEquals("2", KeycloakEnvironment.sql(logRows));

        // The last step fails, because the root still has a child.
        HttpResponse<String> response = scratch.api.post("/reorganizations", """
                {"effectiveDate": "2027-04-01", "steps": [
                  {"op": "change", "id": "%s", "name": "Renamed"},
                  {"op": "create", "ref": "new", "name": "New"},
                  {"op": "end", "id": "%s"}
                ]}
                """.formatted(child, root));

        assertEquals(409, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("org_unit.has_children", error.get("error").asText());
        assertEquals("2", error.get("params").get("step").asText());
        assertEquals(before, scratch.api.get("/org-units?asOf=2030-01-01").body());
        assertEquals("2", KeycloakEnvironment.sql(rows));
        assertEquals("2", KeycloakEnvironment.sql(logRows));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        assertEquals(400, api.post("/reorganizations", "{\"steps\": [{\"op\": \"end\", \"id\": \"" + oldOffice + "\"}]}").statusCode());
        assertEquals(400, api.post("/reorganizations", "{\"effectiveDate\": \"2027-04-01\", \"steps\": []}").statusCode());
        HttpResponse<String> badRef = api.post("/reorganizations",
                "{\"effectiveDate\": \"2028-04-01\", \"steps\": [{\"op\": \"create\", \"name\": \"x\", \"parentRef\": \"nope\"}]}");
        assertEquals(400, badRef.statusCode());
        assertEquals("reorganization.ref_unknown", Api.json(badRef).get("error").asText());
        HttpResponse<String> badOp = api.post("/reorganizations",
                "{\"effectiveDate\": \"2028-04-01\", \"steps\": [{\"op\": \"merge\", \"id\": \"" + medicine + "\"}]}");
        assertEquals("invalid.choice", Api.json(badOp).get("error").asText());
        assertEquals(401, new Api(realm.name, null).post("/reorganizations", "{}").statusCode());
    }
}

package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportIT {

    @Test
    void exportsEveryPeriodOfEveryResource() throws Exception {
        TestRealm realm = TestRealm.create("export");
        Api api = realm.api;
        realm.user("clerk");
        assertEquals(200, api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "refItems": [
                   {"kind": "UNIT_TYPE", "code": "hospital", "name": "病院"},
                   {"kind": "OCCUPATION", "code": "retired", "name": "廃止職種", "active": false}
                 ],
                 "orgUnits": [
                   {"externalId": "H", "name": "X病院", "unitType": "hospital"},
                   {"externalId": "OLD", "name": "旧事務室", "parentExternalId": "H"}
                 ],
                 "employments": [{"externalId": "E1", "username": "clerk", "staffNumber": "C-1"}],
                 "assignments": [{"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "OLD", "primary": true}]}
                """).statusCode());
        String order = Api.json(api.post("/change-orders",
                "{\"title\": \"2027年度\", \"effectiveDate\": \"2027-04-01\"}")).get("id").asText();
        // In April 2027 the hospital gets a new name, the clerk leaves, and the old office ends.
        assertEquals(200, api.post("/imports?changeOrder=" + order, """
                {"effectiveDate": "2027-04-01", "source": "sheet",
                 "orgUnits": [{"externalId": "H", "name": "X総合病院", "unitType": "hospital"}],
                 "employments": [{"externalId": "E1", "username": "clerk", "validTo": "2027-04-01"}]}
                """).statusCode());
        assertEquals(200, api.post("/imports?changeOrder=" + order, """
                {"effectiveDate": "2027-04-01", "source": "sheet",
                 "orgUnits": [{"externalId": "OLD", "name": "旧事務室", "parentExternalId": "H", "validTo": "2027-04-01"}]}
                """).statusCode());

        HttpResponse<String> response = api.get("/export");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode export = Api.json(response);
        assertTrue(export.get("exportedAt").asText().endsWith("Z"));
        assertEquals(List.of("hospital", "retired"), export.get("refItems").findValuesAsText("code"));
        assertFalse(export.get("refItems").get(1).get("active").asBoolean());

        JsonNode hospital = export.get("orgUnits").get(0);
        JsonNode oldOffice = export.get("orgUnits").get(1);
        if (!"H".equals(hospital.get("externalId").asText())) {
            JsonNode swap = hospital;
            hospital = oldOffice;
            oldOffice = swap;
        }
        assertEquals("sheet", hospital.get("source").asText());
        assertEquals(List.of("X病院", "X総合病院"), hospital.get("versions").findValuesAsText("name"));
        assertEquals("2027-04-01", hospital.get("versions").get(0).get("validTo").asText());
        assertEquals("2027-04-01", oldOffice.get("versions").get(0).get("validTo").asText());
        assertEquals(hospital.get("id").asText(), oldOffice.get("parents").get(0).get("parentId").asText());
        assertEquals("2027-04-01", oldOffice.get("parents").get(0).get("validTo").asText());

        JsonNode employment = export.get("employments").get(0);
        assertEquals("E1", employment.get("externalId").asText());
        assertEquals("C-1", employment.get("staffNumber").asText());
        assertEquals("2027-04-01", employment.get("validTo").asText());
        JsonNode assignment = export.get("assignments").get(0);
        assertEquals("A1", assignment.get("externalId").asText());
        assertEquals(oldOffice.get("id").asText(), assignment.get("orgUnitId").asText());
        assertEquals("2027-04-01", assignment.get("validTo").asText());
        assertEquals(List.of("2027年度"), export.get("changeOrders").findValuesAsText("title"));
    }

    @Test
    void anEmptyRealmExportsEmptyLists() throws Exception {
        TestRealm realm = TestRealm.create("export-empty");

        JsonNode export = Api.json(realm.api.get("/export"));

        for (String list : List.of("refItems", "orgUnits", "employments", "assignments", "changeOrders")) {
            assertEquals(0, export.get(list).size(), list);
        }
        assertEquals(401, new Api(realm.name, null).get("/export").statusCode());
    }
}

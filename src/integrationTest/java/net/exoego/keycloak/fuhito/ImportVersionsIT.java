package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportVersionsIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response);
    }

    private static long version(HttpResponse<String> response) throws Exception {
        return ok(response).get("version").asLong();
    }

    @Test
    void anImportCountsUpAResourceOnceAlsoWhenSeveralRowsWriteIt() throws Exception {
        TestRealm realm = TestRealm.create("versions-rows");
        Api api = realm.api;
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "hierarchies": [{"externalId": "project", "code": "project", "name": "プロジェクト"}],
                 "orgUnits": [{"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "P"}}}]}"""));
        String stored = ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=sheet&externalId=P"))
                .get("resourceId").asText();
        assertEquals(1, version(api.get("/org-units/" + stored)));

        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "source": "sheet",
                 "refItems": [
                   {"externalId": "JOB_TITLE.m1", "kind": "JOB_TITLE", "code": "m1", "primaryLanguage": "ja", "names": {"ja": {"name": "課長"}}, "validFrom": "2015-04-01"},
                   {"externalId": "JOB_TITLE.m1", "kind": "JOB_TITLE", "code": "m1", "primaryLanguage": "ja", "names": {"ja": {"name": "マネージャー"}}, "validFrom": "2023-04-01"}
                 ],
                 "orgUnits": [
                   {"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "P2"}}},
                   {"externalId": "OLD", "primaryLanguage": "ja", "names": {"ja": {"name": "旧"}}, "validFrom": "2020-04-01", "validTo": "2027-04-01", "successorExternalIds": ["NEW"]},
                   {"externalId": "NEW", "primaryLanguage": "ja", "names": {"ja": {"name": "新"}}}
                 ],
                 "orgUnitParents": [{"hierarchy": "project", "orgUnitExternalId": "P", "parentExternalId": "NEW"}]}"""));

        String item = ok(api.get("/external-ids/lookup?resourceType=REF_ITEM&source=sheet&externalId=JOB_TITLE.m1"))
                .get("resourceId").asText();
        String old = ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=sheet&externalId=OLD"))
                .get("resourceId").asText();
        assertEquals(1, version(api.get("/ref-items/" + item)));
        assertEquals(1, version(api.get("/org-units/" + old + "?asOf=2026-04-01")));
        assertEquals(2, version(api.get("/org-units/" + stored + "?asOf=2027-04-01")));
    }
}

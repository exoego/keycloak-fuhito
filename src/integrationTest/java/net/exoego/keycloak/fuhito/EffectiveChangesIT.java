package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A ward that opens in April 2030, gets a new name in October, and loses its nurse in April 2031.
 * Everything is stored long before those dates.
 */
class EffectiveChangesIT {

    private static TestRealm realm;
    private static Api api;
    private static String hospital;
    private static String ward;
    private static String employment;
    private static String assignment;
    private static String qualification;

    @BeforeAll
    static void storeTheFuture() throws Exception {
        realm = TestRealm.withEmployer("effective");
        api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String nurse = realm.user("nurse");
        hospital = id(api.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2030-04-01\"}"));
        ward = id(api.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2030-04-01\"}"));
        assertEquals(200, api.putWithVersion("/org-units/" + ward, "{\"name\": \"東病棟\", \"validFrom\": \"2030-10-01\"}").statusCode());
        JsonNode added = Api.json(api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2030-04-01\"}"));
        employment = added.get("employment").get("id").asText();
        assignment = added.get("assignment").get("id").asText();
        assertEquals(200, api.putWithVersion("/assignments/" + assignment + "/end", "{\"validTo\": \"2031-04-01\"}").statusCode());
        qualification = id(api.post("/qualifications", "{\"userId\": \"" + nurse
                + "\", \"kind\": \"license\", \"validFrom\": \"2030-05-01\", \"validTo\": \"2035-05-01\"}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static List<String> entries(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        List<String> entries = new ArrayList<>();
        Api.json(response).forEach(entry -> entries.add(entry.get("effectiveDate").asText() + " "
                + entry.get("resourceType").asText() + " " + entry.get("resourceId").asText()));
        return entries;
    }

    @Test
    void listsWhatStartsOnOneDate() throws Exception {
        List<String> units = new ArrayList<>(List.of(hospital, ward));
        units.sort(null);

        HttpResponse<String> response = api.get("/effective-changes?from=2030-04-01");

        assertEquals(List.of("2030-04-01 FUHITO_ASSIGNMENT " + assignment, "2030-04-01 FUHITO_EMPLOYMENT " + employment,
                "2030-04-01 FUHITO_ORG_UNIT " + units.get(0), "2030-04-01 FUHITO_ORG_UNIT " + units.get(1)),
                entries(response));
        assertEquals("fuhito/assignments/" + assignment, Api.json(response).get(0).get("resourcePath").asText());
    }

    @Test
    void aChangeAnEndAndAnExpiryEachComeOnceOnTheirDate() throws Exception {
        assertEquals(List.of("2030-10-01 FUHITO_ORG_UNIT " + ward),
                entries(api.get("/effective-changes?from=2030-10-01&to=2030-10-02")));
        assertEquals(List.of("2030-05-01 FUHITO_QUALIFICATION " + qualification, "2030-10-01 FUHITO_ORG_UNIT " + ward,
                "2031-04-01 FUHITO_ASSIGNMENT " + assignment, "2035-05-01 FUHITO_QUALIFICATION " + qualification),
                entries(api.get("/effective-changes?from=2030-04-02&to=2040-01-01")));
        assertEquals(List.of(), entries(api.get("/effective-changes?from=2030-04-02&to=2030-05-01")));
        // The end of one version and the start of the next count once, as in the list.
        assertEquals(1, api.count("/effective-changes/count?from=2030-10-01&to=2030-10-02"));
        assertEquals(4, api.count("/effective-changes/count?from=2030-04-02&to=2040-01-01"));
    }

    @Test
    void pagesTheEntries() throws Exception {
        List<String> all = entries(api.get("/effective-changes?from=2030-01-01&to=2040-01-01"));

        assertEquals(8, all.size());
        assertEquals(8, api.count("/effective-changes/count?from=2030-01-01&to=2040-01-01"));
        assertEquals(4, api.count("/effective-changes/count?from=2030-04-01"));
        assertEquals(all.subList(3, 5), entries(api.get("/effective-changes?from=2030-01-01&to=2040-01-01&first=3&max=2")));
    }

    @Test
    void theDefaultRangeIsTodayAndBadRangesAreRejected() throws Exception {
        assertEquals(List.of(), entries(api.get("/effective-changes")));
        assertEquals(List.of(), entries(TestRealm.withEmployer("effective").api.get("/effective-changes?from=2030-04-01")));
        assertEquals(400, api.get("/effective-changes?from=nope").statusCode());
        assertEquals(400, api.get("/effective-changes?from=2030-04-02&to=2030-04-02").statusCode());
        assertEquals(400, api.get("/effective-changes/count?from=2030-04-02&to=2030-04-02").statusCode());
        assertEquals(0, api.count("/effective-changes/count"));
        assertEquals(401, new Api(realm.name, null).get("/effective-changes").statusCode());
    }
}

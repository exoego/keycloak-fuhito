package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RefDataInAttributesIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}";

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode error(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static void kind(Api api, String code) throws Exception {
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"" + code + "\", " + NAMES.formatted(code) + "}")
                .statusCode());
    }

    private static String definition(Api api, String target, String name, String refKind, boolean multiple)
            throws Exception {
        return created(api.post("/attribute-definitions", "{\"target\": \"" + target + "\", \"name\": \"" + name
                + "\", \"type\": \"REF_ITEM\", \"refKind\": \"" + refKind + "\", \"multiple\": " + multiple + "}"));
    }

    private static void assertInUse(Api api, String id, String target, String name) throws Exception {
        JsonNode error = error(409, api.deleteWithVersion("/ref-items/" + id));
        assertEquals("ref_item.in_use", error.get("error").asText());
        assertEquals(List.of(id, target, name), List.of(error.get("params").get("id").asText(),
                error.get("params").get("target").asText(), error.get("params").get("name").asText()));
    }

    @Test
    void anItemStaysWhileAnAttributeOfItsKindHasItsCode() throws Exception {
        TestRealm realm = TestRealm.withEmployer("ref-item-in-attributes");
        Api api = realm.api;
        kind(api, "site");
        kind(api, "region");
        realm.refItem("QUALIFICATION", "license", "免許");
        for (String target : List.of("ORG_UNIT", "PERSON", "EMPLOYMENT_TERMS", "ASSIGNMENT", "QUALIFICATION",
                "REF_ITEM")) {
            definition(api, target, "site", "site", false);
        }
        definition(api, "EMPLOYMENT_TERMS", "sites", "site", true);
        definition(api, "PERSON", "region", "region", false);
        String tokyo = realm.refItem("site", "tokyo", "東京");
        String osaka = realm.refItem("site", "osaka", "大阪");
        String nagoya = realm.refItem("site", "nagoya", "名古屋");
        String kyoto = realm.refItem("site", "kyoto", "京都");
        String fukuoka = realm.refItem("site", "fukuoka", "福岡");
        String kobe = realm.refItem("site", "kobe", "神戸");
        String sapporo = realm.refItem("site", "sapporo", "札幌");
        String sendai = realm.refItem("site", "sendai", "仙台");
        String unused = realm.refItem("site", "naha", "那覇");
        String region = realm.refItem("region", "tokyo", "東京");

        String person = created(api.post("/people", "{" + NAMES.formatted("山田 花子")
                + ", \"attributes\": {\"site\": \"tokyo\"}}"));
        String user = realm.user("nurse");
        String employment = created(api.post("/employments", "{\"userId\": \"" + user + "\", "
                + "\"validFrom\": \"2020-04-01\", \"attributes\": {\"sites\": [\"nagoya\", \"osaka\"]}}"));
        String unit = created(api.post("/org-units", "{" + NAMES.formatted("京都支店")
                + ", \"validFrom\": \"2020-04-01\", \"attributes\": {\"site\": \"kyoto\"}}"));
        assertEquals(200, api.putWithVersion("/org-units/" + unit, "{" + NAMES.formatted("京都支店")
                + ", \"validFrom\": \"2021-04-01\", \"attributes\": {}}").statusCode());
        assertFalse(Api.json(api.get("/org-units/" + unit)).path("attributes").has("site"));
        created(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2021-04-01\", \"primary\": true, \"attributes\": {\"site\": \"fukuoka\"}}"));
        created(api.post("/qualifications", "{\"userId\": \"" + user + "\", \"kind\": \"license\", "
                + "\"validFrom\": \"2020-04-01\", \"attributes\": {\"site\": \"kobe\"}}"));
        created(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"hakodate\", " + NAMES.formatted("函館")
                + ", \"attributes\": {\"site\": \"sapporo\"}}"));
        assertEquals(200, api.putWithVersion("/ref-items/" + sendai, "{" + NAMES.formatted("仙台")
                + ", \"attributes\": {\"site\": \"sendai\"}}").statusCode());

        assertInUse(api, tokyo, "PERSON", "site");
        assertInUse(api, osaka, "EMPLOYMENT_TERMS", "sites");
        assertInUse(api, nagoya, "EMPLOYMENT_TERMS", "sites");
        // Only the version before 2021 has it.
        assertInUse(api, kyoto, "ORG_UNIT", "site");
        assertInUse(api, fukuoka, "ASSIGNMENT", "site");
        assertInUse(api, kobe, "QUALIFICATION", "site");
        assertInUse(api, sapporo, "REF_ITEM", "site");

        // The person has the code of another kind, and an item may name itself.
        assertEquals(204, api.deleteWithVersion("/ref-items/" + region).statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-items/" + sendai).statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-items/" + unused).statusCode());

        assertEquals(200, api.putWithVersion("/people/" + person, "{" + NAMES.formatted("山田 花子")
                + ", \"attributes\": {}}").statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-items/" + tokyo).statusCode());
    }

    @Test
    void aKindStaysWhileADefinitionHasIt() throws Exception {
        TestRealm realm = TestRealm.create("ref-kind-in-definition");
        Api api = realm.api;
        kind(api, "shift-system");
        String definition = definition(api, "EMPLOYMENT_TERMS", "shiftSystem", "shift-system", false);

        JsonNode error = error(409, api.deleteWithVersion("/ref-kinds/shift-system"));
        assertEquals("ref_kind.in_use", error.get("error").asText());
        assertEquals(List.of("shift-system", "EMPLOYMENT_TERMS", "shiftSystem"), List.of(
                error.get("params").get("code").asText(), error.get("params").get("target").asText(),
                error.get("params").get("name").asText()));

        assertEquals(204, api.deleteWithVersion("/attribute-definitions/" + definition).statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-kinds/shift-system").statusCode());
        assertEquals(404, api.get("/ref-kinds/shift-system").statusCode());
    }
}

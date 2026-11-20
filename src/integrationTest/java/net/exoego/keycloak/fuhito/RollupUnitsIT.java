package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A company with a Tokyo branch, and the region Kanto that gathers the branch in the hierarchy "region".
 */
class RollupUnitsIT {

    private static final String ON = "asOf=2026-05-01";

    private record Realm(Api api, String type, String company, String tokyo, String kanto) {
    }

    private static Realm realm(String prefix) throws Exception {
        TestRealm realm = TestRealm.create(prefix);
        Api api = realm.api;
        realm.refItem("UNIT_TYPE", "branch", "支店");
        String type = created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"region\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"地域\"}}, \"rollup\": true}"))
                .get("id").asText();
        assertEquals(201, api.post("/hierarchies", "{\"code\": \"region\", \"name\": \"地域\"}").statusCode());
        String company = unit(api, "本社", null, null);
        String tokyo = unit(api, "東京支店", "branch", company);
        String kanto = unit(api, "関東", "region", null);
        assertEquals(200, api.putWithVersion("/org-units/" + tokyo + "/parent?hierarchy=region",
                move(kanto, "2026-04-01")).statusCode());
        return new Realm(api, type, company, tokyo, kanto);
    }

    private static String unit(Api api, String name, String unitType, String parentId) throws Exception {
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}" + (unitType == null ? "" : ", \"unitType\": \"" + unitType + "\"")
                + (parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"")
                + ", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
    }

    private static String details(String name, String unitType, String validFrom) {
        return "{\"validFrom\": \"" + validFrom + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}, \"unitType\": \"" + unitType + "\"}";
    }

    private static String move(String parentId, String validFrom) {
        return "{\"parentId\": \"" + parentId + "\", \"validFrom\": \"" + validFrom + "\"}";
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static List<String> names(Api api, String path) throws Exception {
        HttpResponse<String> response = api.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return Api.names(Api.json(response));
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void theReadsOfTheDefaultTreeLeaveARollupUnitOut() throws Exception {
        Realm realm = realm("rollup-reads");
        Api api = realm.api();
        String kanto = realm.kanto();

        JsonNode read = Api.json(api.get("/org-units/" + kanto + "?" + ON));
        assertTrue(read.get("rollup").asBoolean(), read.toString());
        assertFalse(read.has("parentId"), read.toString());
        assertFalse(Api.json(api.get("/org-units/" + realm.tokyo() + "?" + ON)).has("rollup"));
        assertTrue(Api.json(api.get("/ref-items/" + realm.type())).get("rollup").asBoolean());
        assertEquals(List.of("本社"), names(api, "/org-units?roots=true&" + ON));
        assertEquals(1, api.count("/org-units/count?roots=true&" + ON));
        assertEquals(List.of(), names(api, "/org-units?roots=true&rollup=true&" + ON));
        assertEquals(List.of(), names(api, "/org-units/" + kanto + "/subtree?" + ON));
        assertEquals(0, api.count("/org-units/" + kanto + "/subtree/count?" + ON));
        assertEquals(List.of(), names(api, "/org-units/" + kanto + "/path?" + ON));
        assertEquals(List.of(), names(api, "/org-units/" + kanto + "/children?" + ON));
        assertEquals(List.of("本社", "東京支店"), names(api, "/org-units/" + realm.tokyo() + "/path?" + ON));
        // The list of every unit has it, and picks it with rollup.
        assertEquals(List.of("関東"), names(api, "/org-units?rollup=true&" + ON));
        assertEquals(1, api.count("/org-units/count?rollup=true&" + ON));
        assertEquals(Set.of("本社", "東京支店"), Set.copyOf(names(api, "/org-units?rollup=false&" + ON)));
        assertEquals(3, api.count("/org-units/count?" + ON));
        // In the other hierarchy, it gathers the branch.
        assertEquals(List.of("関東"), names(api, "/org-units?roots=true&hierarchy=region&" + ON));
        assertEquals(List.of("関東", "東京支店"), names(api, "/org-units/" + kanto + "/subtree?hierarchy=region&" + ON));
        assertEquals(2, api.count("/org-units/" + kanto + "/subtree/count?hierarchy=region&" + ON));
        assertEquals(List.of("関東", "東京支店"), names(api, "/org-units/" + realm.tokyo() + "/path?hierarchy=region&"
                + ON));

        // A unit that leaves the rollup type is a root of the default hierarchy from that date.
        assertEquals(200, api.putWithVersion("/org-units/" + kanto, details("関東", "branch", "2026-10-01"))
                .statusCode());
        assertEquals(List.of("本社"), names(api, "/org-units?roots=true&asOf=2026-09-30"));
        assertEquals(Set.of("関東", "本社"), Set.copyOf(names(api, "/org-units?roots=true&asOf=2026-10-01")));
        assertEquals(List.of("関東"), names(api, "/org-units/" + kanto + "/subtree?asOf=2026-10-01"));
        assertEquals(200, api.putWithVersion("/org-units/" + kanto + "/parent", move(realm.company(), "2026-10-01"))
                .statusCode());
    }

    @Test
    void aRollupUnitGetsNoParentOrChildInTheDefaultHierarchy() throws Exception {
        Realm realm = realm("rollup-writes");
        Api api = realm.api();
        String kanto = realm.kanto();

        assertEquals("org_unit.rollup_has_parent", errorOf(409, api.post("/org-units", "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"関西\"}}, \"unitType\": \"region\", \"parentId\": \""
                + realm.company() + "\", \"validFrom\": \"2026-04-01\"}")));
        // The error names the rollup unit.
        HttpResponse<String> child = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"横浜支店\"}}, \"parentId\": \"" + kanto + "\", \"validFrom\": \"2026-04-01\"}");
        assertEquals("org_unit.rollup_has_child", errorOf(409, child));
        assertTrue(Api.json(child).get("message").asText().contains(kanto), child.body());
        HttpResponse<String> moved = api.putWithVersion("/org-units/" + kanto + "/parent",
                move(realm.company(), "2026-06-01"));
        assertEquals("org_unit.rollup_has_parent", errorOf(409, moved));
        assertTrue(Api.json(moved).get("message").asText().contains(kanto), moved.body());
        assertEquals("org_unit.rollup_has_child", errorOf(409, api.putWithVersion("/org-units/" + realm.tokyo()
                + "/parent", move(kanto, "2026-06-01"))));
        // A unit with a parent or a child cannot get a rollup type.
        HttpResponse<String> typed = api.putWithVersion("/org-units/" + realm.tokyo(),
                details("東京支店", "region", "2026-06-01"));
        assertEquals("org_unit.rollup_has_parent", errorOf(409, typed));
        assertTrue(Api.json(typed).get("message").asText().contains(realm.tokyo()), typed.body());
        assertEquals("org_unit.rollup_has_child", errorOf(409, api.putWithVersion("/org-units/" + realm.company(),
                details("本社", "region", "2026-06-01"))));

        // The mark goes on a unit type alone, and not with lateral or legalEntity. It stays while a unit has it.
        assertEquals("ref_item.rollup_lateral", errorOf(400, api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", "
                + "\"code\": \"area\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"圏\"}}, "
                + "\"rollup\": true, \"lateral\": true}")));
        assertEquals("ref_item.rollup_legal_entity", errorOf(400, api.post("/ref-items", "{\"kind\": "
                + "\"UNIT_TYPE\", \"code\": \"group\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"グループ\"}}, \"rollup\": true, \"legalEntity\": true}")));
        assertEquals("ref_item.rollup_kind", errorOf(400, api.post("/ref-items", "{\"kind\": \"JOB_TITLE\", "
                + "\"code\": \"chief\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"長\"}}, "
                + "\"rollup\": true}")));
        assertEquals("ref_item.rollup_in_use", errorOf(409, api.putWithVersion("/ref-items/" + realm.type(),
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"地域\"}}}")));
    }

    @Test
    void anImportTakesTheMarkAndKeepsTheRulesOfRollupUnits() throws Exception {
        TestRealm realm = TestRealm.create("rollup-import");
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "hierarchies": [{"externalId": "region", "code": "region", "name": "地域"}],
                 "refItems": [
                   {"externalId": "UNIT_TYPE.region", "kind": "UNIT_TYPE", "code": "region", "primaryLanguage": "ja", "names": {"ja": {"name": "地域"}}, "rollup": true}
                 ],
                 "orgUnits": [
                   {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "本社"}}},
                   {"externalId": "B", "primaryLanguage": "ja", "names": {"ja": {"name": "東京支店"}}, "parentExternalId": "H"},
                   {"externalId": "K", "primaryLanguage": "ja", "names": {"ja": {"name": "関東"}}, "unitType": "region"}
                 ],
                 "orgUnitParents": [{"hierarchy": "region", "orgUnitExternalId": "B", "parentExternalId": "K"}]}
                """;

        JsonNode first = ImportIT.ok(realm.api.post("/imports", document));
        JsonNode again = ImportIT.ok(realm.api.post("/imports", document));

        assertEquals(3, first.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(3, again.get("counts").get("orgUnits").get("unchanged").asInt());
        assertTrue(Api.json(realm.api.get("/ref-items?kind=UNIT_TYPE")).get(0).get("rollup").asBoolean());
        assertEquals(List.of("本社", "東京支店"), Api.names(realm.api.tree("asOf=2026-04-01")));
        assertEquals(List.of("関東", "東京支店"), Api.names(realm.api.tree("asOf=2026-04-01&hierarchy=region")));

        HttpResponse<String> bad = realm.api.post("/imports", """
                {"effectiveDate": "2026-05-01", "source": "sheet", "orgUnits": [
                  {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "関西"}}, "unitType": "region", "parentExternalId": "H"},
                  {"externalId": "Y", "primaryLanguage": "ja", "names": {"ja": {"name": "横浜支店"}}, "parentExternalId": "K"}
                ]}
                """);
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals(List.of("org_unit.rollup_has_parent", "org_unit.rollup_has_child"),
                Api.json(bad).get("errors").findValuesAsText("error"));
    }
}

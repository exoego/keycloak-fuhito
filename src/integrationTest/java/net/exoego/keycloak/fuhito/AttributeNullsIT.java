package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A name with null in {@code attributes} has no value. Every write stores the attributes without it.
 */
class AttributeNullsIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}";

    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("ORG_UNIT", "fuhito_org_unit_version");
        TABLES.put("PERSON", "fuhito_person");
        TABLES.put("EMPLOYMENT_TERMS", "fuhito_employment_terms");
        TABLES.put("ASSIGNMENT", "fuhito_assignment");
        TABLES.put("QUALIFICATION", "fuhito_qualification");
        TABLES.put("REF_ITEM", "fuhito_ref_item");
    }

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static void changed(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
    }

    private static String error(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    // The rows of the realm whose attributes have the name, also with null.
    private static String rowsWith(TestRealm realm, String target, String name) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + TABLES.get(target) + " where realm_id = '"
                + realm.id() + "' and cast(attributes as json) -> '" + name + "' is not null");
    }

    private static String attributesOf(String table, String id) throws Exception {
        return KeycloakEnvironment.sql("select attributes from " + table + " where id = '" + id + "'");
    }

    private static Map<String, String> define(Api api, String name, String rest) throws Exception {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (String target : TABLES.keySet()) {
            definitions.put(target, created(api.post("/attribute-definitions", "{\"target\": \"" + target
                    + "\", \"name\": \"" + name + "\", \"type\": \"STRING\"" + rest + "}")));
        }
        return definitions;
    }

    @Test
    void everyRestWriteStoresTheAttributesWithoutANameWithNull() throws Exception {
        TestRealm realm = TestRealm.withEmployer("attribute-nulls");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        Map<String, String> notes = define(api, "note", "");
        String note = "\"attributes\": {\"note\": null}";

        String person = created(api.post("/people", "{" + NAMES.formatted("山田 花子")
                + ", \"attributes\": {\"note\": \"a\"}}"));
        changed(api.putWithVersion("/people/" + person, "{" + NAMES.formatted("山田 花子") + ", " + note + "}"));
        created(api.post("/people", "{" + NAMES.formatted("佐藤 次郎") + ", " + note + "}"));
        String user = realm.user("nurse");
        String employment = created(api.post("/employments", "{\"userId\": \"" + user + "\", "
                + "\"validFrom\": \"2020-04-01\", " + note + "}"));
        changed(api.putWithVersion("/employments/" + employment + "/terms", "{\"validFrom\": \"2021-04-01\", "
                + "\"workPercent\": 80, " + note + "}"));
        String unit = created(api.post("/org-units", "{" + NAMES.formatted("京都支店")
                + ", \"validFrom\": \"2020-04-01\", " + note + "}"));
        changed(api.putWithVersion("/org-units/" + unit, "{" + NAMES.formatted("京都支店")
                + ", \"validFrom\": \"2021-04-01\", " + note + "}"));
        String assignment = created(api.post("/assignments", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2021-04-01\", \"primary\": true, " + note
                + "}"));
        changed(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2022-04-01\", "
                + "\"head\": true, " + note + "}"));
        String qualification = created(api.post("/qualifications", "{\"userId\": \"" + user
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\", " + note + "}"));
        changed(api.putWithVersion("/qualifications/" + qualification, "{\"kind\": \"license\", "
                + "\"validFrom\": \"2020-04-01\", \"number\": \"1\", " + note + "}"));
        String item = created(api.post("/ref-items", "{\"kind\": \"QUALIFICATION\", \"code\": \"radiology\", "
                + NAMES.formatted("放射線") + ", " + note + "}"));
        changed(api.putWithVersion("/ref-items/" + item, "{" + NAMES.formatted("放射線技師") + ", " + note + "}"));

        assertAll(TABLES.keySet().stream().map(target -> () -> assertEquals("0", rowsWith(realm, target, "note"),
                target)));
        // No row has the name, so a delete needs no clearValues.
        for (String target : TABLES.keySet()) {
            assertEquals(204, api.deleteWithVersion("/attribute-definitions/" + notes.get(target)).statusCode(),
                    target);
        }
    }

    @Test
    void aRequiredNameWithNullFailsEveryRestWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("attribute-nulls-required");
        Api api = realm.api;
        String item = realm.refItem("QUALIFICATION", "license", "免許");
        String person = created(api.post("/people", "{" + NAMES.formatted("山田 花子") + "}"));
        String user = realm.user("nurse");
        String employment = created(api.post("/employments", "{\"userId\": \"" + user + "\", "
                + "\"validFrom\": \"2020-04-01\"}"));
        String unit = created(api.post("/org-units", "{" + NAMES.formatted("京都支店")
                + ", \"validFrom\": \"2020-04-01\"}"));
        String assignment = created(api.post("/assignments", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2021-04-01\", \"primary\": true}"));
        String qualification = created(api.post("/qualifications", "{\"userId\": \"" + user
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        define(api, "badge", ", \"required\": true, \"fillValue\": \"x\"");
        String badge = "\"attributes\": {\"badge\": null}";

        List<HttpResponse<String>> writes = List.of(
                api.post("/people", "{" + NAMES.formatted("佐藤 次郎") + ", " + badge + "}"),
                api.putWithVersion("/people/" + person, "{" + NAMES.formatted("山田 花子") + ", " + badge + "}"),
                api.putWithVersion("/employments/" + employment + "/terms", "{\"validFrom\": \"2021-04-01\", "
                        + "\"workPercent\": 80, " + badge + "}"),
                api.post("/org-units", "{" + NAMES.formatted("大阪支店") + ", \"validFrom\": \"2020-04-01\", "
                        + badge + "}"),
                api.putWithVersion("/org-units/" + unit, "{" + NAMES.formatted("京都支店")
                        + ", \"validFrom\": \"2021-04-01\", " + badge + "}"),
                api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2022-04-01\", "
                        + "\"head\": true, " + badge + "}"),
                api.putWithVersion("/qualifications/" + qualification, "{\"kind\": \"license\", "
                        + "\"validFrom\": \"2020-04-01\", " + badge + "}"),
                api.post("/ref-items", "{\"kind\": \"QUALIFICATION\", \"code\": \"radiology\", "
                        + NAMES.formatted("放射線") + ", " + badge + "}"),
                api.putWithVersion("/ref-items/" + item, "{" + NAMES.formatted("免許") + ", " + badge + "}"));

        for (HttpResponse<String> write : writes) {
            assertEquals("attribute.required", error(write), write.request().uri().toString());
        }
    }

    @Test
    void anImportAndARestWriteOfTheSameValuesStoreTheSameRow() throws Exception {
        TestRealm realm = TestRealm.create("attribute-nulls-import");
        Api api = realm.api;
        realm.attribute("PERSON", "note", "STRING");
        realm.attribute("PERSON", "rank", "STRING");
        String attributes = "\"attributes\": {\"note\": null, \"rank\": \"a\"}";

        String written = created(api.post("/people", "{" + NAMES.formatted("山田 花子") + ", " + attributes + "}"));
        ok(api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"people\": [{\"externalId\": \"P1\", "
                + NAMES.formatted("佐藤 次郎") + ", " + attributes + "}]}"));
        String imported = KeycloakEnvironment.sql("select id from fuhito_person where realm_id = '" + realm.id()
                + "' and id <> '" + written + "'");

        assertEquals("{\"rank\":\"a\"}", attributesOf("fuhito_person", written));
        assertEquals(attributesOf("fuhito_person", written), attributesOf("fuhito_person", imported));
    }

    // A write before the rule of null kept the name with null. Such a row has no value.
    @Test
    void aStoredNullBlocksNoDeleteAndAnExportAndARestoreLeaveItOut() throws Exception {
        TestRealm realm = TestRealm.create("attribute-nulls-stored");
        Api api = realm.api;
        String note = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"note\", "
                + "\"type\": \"STRING\"}"));
        String person = created(api.post("/people", "{" + NAMES.formatted("山田 花子")
                + ", \"attributes\": {\"note\": \"a\"}}"));
        KeycloakEnvironment.sql("update fuhito_person set attributes = '{\"note\":null}' where id = '" + person
                + "'");

        assertEquals(204, api.deleteWithVersion("/attribute-definitions/" + note).statusCode());
        assertEquals(1, api.version("/people/" + person));
        ObjectNode document = RealmExportIT.export(api);
        assertEquals("{}", document.get("people").get(0).get("attributes").toString(), document.toString());
        HttpResponse<String> restored = api.post("/restore?replace=true", document.toString());
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals("{}", attributesOf("fuhito_person", person));
    }
}

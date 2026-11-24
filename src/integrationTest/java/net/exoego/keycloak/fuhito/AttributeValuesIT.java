package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.FixedValuesIT.outcome;
import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.sql;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write of a definition keeps the stored values valid. A new required attribute, or one that becomes required,
 * fills each row with no value, also a row of a past version. A delete removes the values first, or fails while
 * they are there.
 */
class AttributeValuesIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"名\"}}";

    // One resource of each kind. The org unit, the terms of the employment, and the assignment get a second version
    // in 2023.
    private static final String SETUP = """
            {"effectiveDate": "2020-04-01",
             "refItems": [{"externalId": "L", "kind": "QUALIFICATION", "code": "license", %1$s}],
             "orgUnits": [{"externalId": "U", "validFrom": "2020-04-01", %1$s}],
             "people": [{"externalId": "P", %1$s}],
             "qualifications": [{"externalId": "Q", "personExternalId": "P", "kind": "license"}],
             "employments": [{"externalId": "E", "personExternalId": "P", "validFrom": "2020-04-01",
               "workPercent": 80}],
             "assignments": [{"externalId": "A", "employmentExternalId": "E", "orgUnitExternalId": "U",
               "validFrom": "2020-04-01", "allocationPercent": 50}]}
            """.formatted(NAMES);

    private static final String LATER = """
            {"effectiveDate": "2023-04-01",
             "orgUnits": [{"externalId": "U", "names": {"ja": {"name": "新"}}}],
             "employments": [{"externalId": "E", "workPercent": 100}],
             "assignments": [{"externalId": "A", "allocationPercent": 100}]}
            """;

    /**
     * A kind of resource with attributes.
     *
     * @param target the target of a definition
     * @param table the table of the rows with attributes
     * @param resource the column of the table that names the resource
     * @param collection the first part of the path of the resource
     * @param idType the type of the resource for its IDs of other systems
     * @param externalId the ID of the resource of the setup in the system "import"
     * @param resourceType the type of the resource in the change log
     * @param rows the number of rows of the resource of the setup
     */
    private record Kind(String target, String table, String resource, String collection, String idType,
            String externalId, String resourceType, int rows) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("ORG_UNIT", "fuhito_org_unit_version", "org_unit_id", "org-units", "ORG_UNIT", "U",
                    "FUHITO_ORG_UNIT", 2),
            new Kind("PERSON", "fuhito_person", "id", "people", "PERSON", "P", "FUHITO_PERSON", 1),
            new Kind("EMPLOYMENT_TERMS", "fuhito_employment_terms", "employment_id", "employments", "EMPLOYMENT",
                    "E", "FUHITO_EMPLOYMENT", 2),
            new Kind("ASSIGNMENT", "fuhito_assignment", "assignment_id", "assignments", "ASSIGNMENT", "A",
                    "FUHITO_ASSIGNMENT", 2),
            new Kind("QUALIFICATION", "fuhito_qualification", "id", "qualifications", "QUALIFICATION", "Q",
                    "FUHITO_QUALIFICATION", 1),
            new Kind("REF_ITEM", "fuhito_ref_item", "id", "ref-items", "REF_ITEM", "L", "FUHITO_REF_ITEM", 1));

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String person(Api api, String attributes) throws Exception {
        return id(api.post("/people", "{" + NAMES + ", \"attributes\": " + attributes + "}"));
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(sql(sql));
    }

    private static String lookup(Api api, String type, String externalId) throws Exception {
        return ok(api.get("/external-ids/lookup?resourceType=" + type + "&source=import&externalId=" + externalId))
                .get("resourceId").asText();
    }

    // The operation and the path of each entry of the change log of one resource.
    private static List<String> logOf(Api api, String resourceType, String id) throws Exception {
        List<String> entries = new ArrayList<>();
        ok(api.get("/change-log?resourceType=" + resourceType + "&resourceId=" + id))
                .forEach(entry -> entries.add(entry.get("operation").asText() + " " + entry.get("resourcePath")
                        .asText()));
        return entries;
    }

    @Test
    void eachKindOfResourceGetsTheValueInEveryVersionAndLosesItWithClearValues() throws Exception {
        TestRealm realm = TestRealm.withEmployer("attribute-values-kinds");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ok(api.post("/imports", LATER));
        String realmId = realm.id();

        for (Kind kind : KINDS) {
            String target = kind.target();
            String id = lookup(api, kind.idType(), kind.externalId());
            String path = "/" + kind.collection() + "/" + id;
            String rows = "from " + kind.table() + " where realm_id = '" + realmId + "'";
            assertEquals(kind.rows(), count("select count(*) " + rows + " and " + kind.resource() + " = '" + id
                    + "'"), target);
            long all = count("select count(*) " + rows);
            long resources = count("select count(distinct " + kind.resource() + ") " + rows);
            long version = api.version(path);
            long logged = api.count("/change-log/count?resourceType=" + kind.resourceType());
            String definition = "{\"target\": \"" + target + "\", \"name\": \"flag\", \"type\": \"BOOLEAN\", "
                    + "\"required\": true%s}";

            assertEquals("400 attribute.fill_value_required " + all,
                    outcome(api.post("/attribute-definitions", definition.formatted("")), "count"), target);
            assertEquals(0, count("select count(*) " + rows + " and attributes like '%\"flag\"%'"), target);
            String created = id(api.post("/attribute-definitions", definition.formatted(", \"fillValue\": true")));
            assertEquals(all, count("select count(*) " + rows + " and attributes like '%\"flag\":true%'"), target);
            assertEquals(version + 1, api.version(path), target);
            assertEquals(logged + resources, api.count("/change-log/count?resourceType=" + kind.resourceType()),
                    target);
            assertEquals("UPDATE fuhito" + path, logOf(api, kind.resourceType(), id).getLast(), target);

            String delete = "/attribute-definitions/" + created;
            assertEquals("409 attribute.in_use " + all, outcome(api.deleteWithVersion(delete), "count"), target);
            assertEquals(204, api.deleteWithVersion(delete + "?clearValues=true").statusCode(), target);
            assertEquals(0, count("select count(*) " + rows + " and attributes like '%\"flag\"%'"), target);
            assertEquals(version + 2, api.version(path), target);
            assertEquals(logged + 2 * resources, api.count("/change-log/count?resourceType=" + kind.resourceType()),
                    target);
            assertEquals(404, api.get(delete).statusCode(), target);
        }
    }

    @Test
    void aFillKeepsTheOtherValuesAsTheyAreStoredAndAFailedFillWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("attribute-values-text");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "size", "NUMBER");
        realm.textList("ORG_UNIT", "tags");
        String unit = id(api.post("/org-units", "{" + NAMES + ", \"validFrom\": \"2020-04-01\", "
                + "\"attributes\": {\"size\": 1e300, \"tags\": [\"a\", \"b\\\"c\"]}}"));
        String rowOfUnit = "select attributes from fuhito_org_unit_version where org_unit_id = '" + unit + "'";
        String stored = sql(rowOfUnit);
        ObjectNode before = RealmExportIT.export(api);
        long logged = api.count("/change-log/count");
        String beds = "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", \"type\": \"%s\", \"required\": true, "
                + "\"fillValue\": %s}";

        assertEquals("400 attribute.fill_value_required 1", outcome(api.post("/attribute-definitions",
                "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", \"type\": \"NUMBER\", \"required\": true}"), "count"));
        assertEquals("400 attribute.bad_value beds", outcome(api.post("/attribute-definitions",
                beds.formatted("NUMBER", "\"30\"")), "name"));
        assertEquals("400 invalid.blank fillValue", outcome(api.post("/attribute-definitions",
                beds.formatted("STRING", "\" \"")), "field"));
        // The value alone fits in 64 KB, but not with the values that the row has.
        assertEquals("400 attribute.too_large", outcome(api.post("/attribute-definitions",
                beds.formatted("STRING", "\"" + "x".repeat(65_500) + "\""))));
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(logged, api.count("/change-log/count"));
        assertEquals(stored, sql(rowOfUnit));

        id(api.post("/attribute-definitions", beds.formatted("NUMBER", "30")));
        assertEquals(stored.substring(0, stored.length() - 1) + ",\"beds\":30}", sql(rowOfUnit));
        JsonNode read = ok(api.get("/org-units/" + unit));
        assertEquals(30, read.get("attributes").get("beds").asInt());
        assertEquals("b\"c", read.get("attributes").get("tags").get(1).asText());
        // A read of the unit is a body of a write.
        ok(api.put("/org-units/" + unit, read.toString()));
    }

    @Test
    void aChangeToRequiredFillsOnlyTheRowsWithNoValue() throws Exception {
        TestRealm realm = TestRealm.create("attribute-values-required");
        Api api = realm.api;
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("OCCUPATION", "clerk", "事務");
        String jobs = id(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"jobs\", "
                + "\"type\": \"REF_ITEM\", \"refKind\": \"OCCUPATION\", \"multiple\": true}"));
        String clerk = person(api, "{\"jobs\": [\"clerk\"]}");
        String none = person(api, "{}");
        // A row with null for the name has no value.
        sql("update fuhito_person set attributes = '{\"jobs\":null}' where id = '" + none + "'");
        String path = "/attribute-definitions/" + jobs;

        assertEquals("400 attribute.fill_value_required 1",
                outcome(api.putWithVersion(path, "{\"required\": true}"), "count"));
        for (String bad : List.of("\"nurse\"", "[\"driver\"]", "[1]", "{\"a\": \"nurse\"}")) {
            assertEquals("400 attribute.bad_value", outcome(api.putWithVersion(path,
                    "{\"required\": true, \"fillValue\": " + bad + "}")), bad);
        }
        assertEquals("400 invalid.blank fillValue", outcome(api.putWithVersion(path,
                "{\"required\": true, \"fillValue\": []}"), "field"));
        assertEquals("400 invalid.not_together fillValue required", outcome(api.putWithVersion(path,
                "{\"required\": false, \"fillValue\": [\"nurse\"]}"), "field", "otherField"));
        assertEquals("400 invalid.not_together fillValue required", outcome(api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"note\", \"type\": \"STRING\", \"fillValue\": \"a\"}"),
                "field", "otherField"));
        assertFalse(ok(api.get(path)).get("required").asBoolean());
        assertEquals(1, api.version("/people/" + none));

        JsonNode changed = ok(api.putWithVersion(path, "{\"required\": true, \"fillValue\": [\"nurse\"]}"));
        assertTrue(changed.get("required").asBoolean());
        assertFalse(changed.has("fillValue"), changed.toString());
        assertEquals("[\"clerk\"]", ok(api.get("/people/" + clerk)).get("attributes").get("jobs").toString());
        assertEquals("[\"nurse\"]", ok(api.get("/people/" + none)).get("attributes").get("jobs").toString());
        assertEquals("{\"jobs\":[\"nurse\"]}", sql("select attributes from fuhito_person where id = '" + none + "'"));
        assertEquals(1, api.version("/people/" + clerk));
        assertEquals(2, api.version("/people/" + none));
        assertEquals(List.of("CREATE fuhito/people/" + clerk), logOf(api, "FUHITO_PERSON", clerk));
        assertEquals(List.of("CREATE fuhito/people/" + none, "UPDATE fuhito/people/" + none),
                logOf(api, "FUHITO_PERSON", none));

        // A read is a body of a write. Every row has a value, so nothing is filled.
        ok(api.put(path, ok(api.get(path)).toString()));
        ok(api.putWithVersion(path, "{\"required\": false}"));
        ok(api.putWithVersion(path, "{\"required\": true, \"fillValue\": [\"clerk\"]}"));
        assertEquals(2, api.version("/people/" + none));
        assertEquals("[\"nurse\"]", ok(api.get("/people/" + none)).get("attributes").get("jobs").toString());
        assertEquals(2, logOf(api, "FUHITO_PERSON", none).size());
        // A later write that leaves the attribute out is not filled.
        assertEquals("400 attribute.required", outcome(api.post("/people", "{" + NAMES + "}")));
    }

    @Test
    void aDeleteCountsEveryRowWithAValueAndAReadIsABodyAfterTheClear() throws Exception {
        TestRealm realm = TestRealm.create("attribute-values-delete");
        Api api = realm.api;
        String note = id(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"note\", "
                + "\"type\": \"STRING\"}"));
        realm.attribute("PERSON", "memo", "STRING");
        String noted = person(api, "{\"memo\": \"y\", \"note\": \"x\"}");
        String other = person(api, "{\"memo\": \"z\"}");
        // A write before the rule of null kept the name with null. Such a row has no value.
        String withNull = person(api, "{\"memo\": \"w\"}");
        sql("update fuhito_person set attributes = '{\"note\":null,\"memo\":\"w\"}' where id = '" + withNull + "'");
        String path = "/attribute-definitions/" + note;

        HttpResponse<String> inUse = api.deleteWithVersion(path);
        assertEquals("409 attribute.in_use 1 note PERSON", outcome(inUse, "count", "name", "target"));
        assertEquals("PERSON の 1 行に属性「note」があります。先に値を消すか、clearValues=true で定義を削除してください。",
                Api.json(api.withAcceptLanguage("ja").deleteWithVersion(path)).get("message").asText());
        assertEquals("x", ok(api.get("/people/" + noted)).get("attributes").get("note").asText());

        assertEquals(204, api.deleteWithVersion(path + "?clearValues=true").statusCode());
        assertEquals("{\"memo\":\"y\"}", sql("select attributes from fuhito_person where id = '" + noted + "'"));
        assertEquals("{\"note\":null,\"memo\":\"w\"}",
                sql("select attributes from fuhito_person where id = '" + withNull + "'"));
        JsonNode read = ok(api.get("/people/" + noted));
        assertFalse(read.get("attributes").has("note"), read.toString());
        assertEquals(2, read.get("version").asLong());
        assertEquals(1, api.version("/people/" + withNull));
        assertEquals(1, api.version("/people/" + other));
        ok(api.put("/people/" + noted, read.toString()));
        assertEquals("400 attribute.unknown", outcome(api.post("/people", "{" + NAMES
                + ", \"attributes\": {\"note\": \"x\"}}")));
    }

    @Test
    void anImportFillsTheRowsAndAFailedImportWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("attribute-values-import");
        Api api = realm.api;
        ok(api.post("/imports", "{\"effectiveDate\": \"2020-04-01\", \"people\": [{\"externalId\": \"P1\", "
                + NAMES + "}, {\"externalId\": \"P2\", " + NAMES + "}]}"));
        String first = lookup(api, "PERSON", "P1");
        String second = lookup(api, "PERSON", "P2");
        ObjectNode before = RealmExportIT.export(api);
        long logged = api.count("/change-log/count");
        String document = """
                {"effectiveDate": "2020-04-01", "dryRun": %s,
                 "attributeDefinitions": [{"externalId": "D", "target": "PERSON", "name": "beds", "type": "NUMBER",
                   "required": true%s}],
                 "people": [%s]}""";

        HttpResponse<String> missing = api.post("/imports", document.formatted(false, "", ""));
        assertEquals(400, missing.statusCode(), missing.body());
        assertEquals(List.of("attribute.fill_value_required"), Api.json(missing).findValuesAsText("error"));
        // A later row fails, so the fill is undone too.
        HttpResponse<String> failed = api.post("/imports", document.formatted(false, ", \"fillValue\": 3",
                "{\"externalId\": \"P3\", " + NAMES + "}"));
        assertEquals(400, failed.statusCode(), failed.body());
        assertEquals(List.of("attribute.required"), Api.json(failed).findValuesAsText("error"));
        ok(api.post("/imports", document.formatted(true, ", \"fillValue\": 3", "")));
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(logged, api.count("/change-log/count"));

        ok(api.post("/imports", document.formatted(false, ", \"fillValue\": 3",
                "{\"externalId\": \"P2\", \"attributes\": {\"beds\": 5}}")));
        assertEquals(3, ok(api.get("/people/" + first)).get("attributes").get("beds").asInt());
        assertEquals(5, ok(api.get("/people/" + second)).get("attributes").get("beds").asInt());
        // The fill and the row of the person are one write of the import, so the version counts up once.
        assertEquals(2, api.version("/people/" + first));
        assertEquals(2, api.version("/people/" + second));
        assertEquals(List.of("CREATE fuhito/people/" + second, "UPDATE fuhito/people/" + second,
                "UPDATE fuhito/people/" + second), logOf(api, "FUHITO_PERSON", second));

        HttpResponse<String> retyped = api.post("/imports", "{\"effectiveDate\": \"2020-04-01\", "
                + "\"attributeDefinitions\": [{\"externalId\": \"D\", \"type\": \"STRING\"}]}");
        assertEquals(400, retyped.statusCode(), retyped.body());
        assertEquals(List.of("import.value_fixed"), Api.json(retyped).findValuesAsText("error"));

        HttpResponse<String> csv = api.postBytes("/imports/csv/attribute-definitions?effectiveDate=2020-04-01",
                "text/csv; charset=UTF-8", """
                        externalId,target,name,type,multiple,required,fillValue
                        T,PERSON,tags,NUMBER,true,true,1|2.50
                        """.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals("[1,2.5]", ok(api.get("/people/" + first)).get("attributes").get("tags").toString());
        assertEquals(3, api.version("/people/" + first));
    }
}

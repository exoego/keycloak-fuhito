package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreRulesIT.assertEachBreaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.at;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.breaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreAttributesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOO_LARGE = "attribute.too_large";

    // More than 64 KiB as JSON.
    private static final String LARGE = "{\"attributes\": {\"note\": \"" + "x".repeat(70_000) + "\"}}";

    @Test
    void attributesOfMoreThan64KiBFailTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-attributes", List.of(
                breaks("ref-items", "role-1", "attributes", TOO_LARGE, at("/refItems/0", LARGE)),
                breaks("org-unit-versions", "v-1", "attributes", TOO_LARGE, at("/orgUnits/0/versions/0", LARGE)),
                breaks("people", "p-1", "attributes", TOO_LARGE, at("/people/0", LARGE)),
                breaks("employment-terms", "t-1", "attributes", TOO_LARGE, at("/employments/0/terms/0", LARGE)),
                breaks("assignments", "r-1", "attributes", TOO_LARGE, at("/assignments/0/periods/0", LARGE)),
                breaks("qualifications", "q-1", "attributes", TOO_LARGE, at("/refItems", item("lic", "QUALIFICATION",
                        "")), at("", "{\"qualifications\": [{\"id\": \"q-1\", \"personId\": \"p-1\", \"kindId\": "
                        + "\"lic\", \"validFrom\": \"2020-04-01\"}]}"), at("/qualifications/0", LARGE))));
    }

    private static String definition(String id, String target, String name, String rest) {
        return "{\"id\": \"" + id + "\", \"target\": \"" + target + "\", \"name\": \"" + name + "\", " + rest + "}";
    }

    private static String attributes(String attributes) {
        return "{\"attributes\": " + attributes + "}";
    }

    // The document defines only the attribute note of a person, of the type STRING.
    @Test
    void attributesThatBreakARuleOfAWriteFailTheRestoreAndNothingIsWritten() throws Exception {
        String unknown = "attribute.unknown";
        String bad = "attribute.bad_value";
        String required = "attribute.required";
        String tags = definition("d-2", "PERSON", "tags", "\"type\": \"STRING\", \"multiple\": true");
        String role = definition("d-2", "PERSON", "role", "\"type\": \"REF_ITEM\", \"refKind\": \"UNIT_ROLE\"");
        String beds = definition("d-2", "ORG_UNIT", "beds", "\"type\": \"NUMBER\"");
        assertEachBreaks("restore-attribute-rules", List.of(
                breaks("people", "p-1", "attributes.beds", unknown, at("/people/0", attributes("{\"beds\": 1}"))),
                breaks("ref-items", "role-1", "attributes.note", unknown,
                        at("/refItems/0", attributes("{\"note\": \"a\"}"))),
                breaks("assignments", "r-1", "attributes.note", unknown,
                        at("/assignments/0/periods/0", attributes("{\"note\": \"a\"}"))),
                breaks("people", "p-1", "attributes.note", bad, at("/people/0", attributes("{\"note\": 1}"))),
                breaks("people", "p-1", "attributes.note", bad, at("/people/0", attributes("{\"note\": [\"a\"]}"))),
                breaks("people", "p-1", "attributes.tags", bad, at("/attributeDefinitions", tags),
                        at("/people/0", attributes("{\"tags\": \"a\"}"))),
                breaks("people", "p-1", "attributes.role", bad, at("/attributeDefinitions", role),
                        at("/people/0", attributes("{\"role\": \"nobody\"}"))),
                breaks("people", "p-1", "attributes.role", bad, at("/attributeDefinitions", role),
                        at("/people/0", attributes("{\"role\": \"corp\"}"))),
                breaks("employment-terms", "t-1", "attributes.rank", required, at("/attributeDefinitions",
                        definition("d-2", "EMPLOYMENT_TERMS", "rank", "\"type\": \"STRING\", \"required\": true"))),
                // v-9 is the past version of an org unit that ended.
                breaks("org-unit-versions", "v-9", "attributes.beds", bad, at("/attributeDefinitions", beds),
                        at("/orgUnits/0/versions/0", attributes("{\"beds\": 1}")),
                        at("/orgUnits/2/versions/0", attributes("{\"beds\": \"ten\"}"))),
                breaks("org-unit-versions", "v-9", "attributes.beds", required, at("/attributeDefinitions",
                        definition("d-2", "ORG_UNIT", "beds", "\"type\": \"NUMBER\", \"required\": true")),
                        at("/orgUnits/0/versions/0", attributes("{\"beds\": 1}")),
                        at("/orgUnits/1/versions/0", attributes("{\"beds\": 2}")))));
    }

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
    }

    @Test
    void anExportOfARealmThatMeetsItsDefinitionsRestoresAsItWas() throws Exception {
        TestRealm source = TestRealm.create("restore-attributes-export");
        Api api = source.api;
        source.refItem("GRADE", "g1", "一級");
        ok(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\", \"required\": true}"));
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"grade\", \"type\": \"REF_ITEM\", "
                + "\"refKind\": \"GRADE\"}"));
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"tags\", \"type\": \"STRING\", "
                + "\"multiple\": true, \"required\": true}"));
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\", \"attributes\": {\"beds\": 10}}"))
                .get("id").asText();
        ok(api.putWithVersion("/org-units/" + unit, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"病棟\"}}, \"validFrom\": \"2024-04-01\", \"attributes\": {\"beds\": 12.5}}"));
        ok(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田\"}}, "
                + "\"attributes\": {\"grade\": \"g1\", \"tags\": [\"a\", \"b\"]}}"));
        ObjectNode before = RealmExportIT.export(api);
        assertEquals(2, before.at("/orgUnits/0/versions").size(), before.toString());

        TestRealm target = TestRealm.create("restore-attributes-target");
        source.remove();
        try {
            ok(target.api.post("/restore", JSON.writeValueAsString(before)));
            assertEquals(before, RealmExportIT.export(target.api));
        } finally {
            target.remove();
        }
    }
}

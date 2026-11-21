package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.sql;
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

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
    }

    private static String id(HttpResponse<String> response) throws Exception {
        ok(response);
        return Api.json(response).get("id").asText();
    }

    // No write of a definition leaves values that it does not allow, so the definitions change in the database.
    @Test
    void valuesThatTheirChangedDefinitionsNoLongerAllowRestoreAsTheyWere() throws Exception {
        TestRealm source = TestRealm.create("restore-attributes-export");
        Api api = source.api;
        String grade = source.refItem("GRADE", "g1", "一級");
        String note = id(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"note\", "
                + "\"type\": \"STRING\"}"));
        String old = id(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"old\", "
                + "\"type\": \"STRING\"}"));
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"grade\", \"type\": \"REF_ITEM\", "
                + "\"refKind\": \"GRADE\"}"));
        ok(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田\"}}, "
                + "\"attributes\": {\"note\": \"text\", \"old\": \"x\", \"grade\": \"g1\"}}"));
        String badge = id(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"badge\", "
                + "\"type\": \"STRING\"}"));
        sql("update fuhito_attribute_definition set value_type = 'NUMBER', multiple = true where id = '" + note + "'");
        sql("delete from fuhito_attribute_definition where id = '" + old + "'");
        sql("update fuhito_attribute_definition set required = true where id = '" + badge + "'");
        ok(api.deleteWithVersion("/ref-items/" + grade));
        ObjectNode before = RealmExportIT.export(api);

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

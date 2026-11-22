package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreRulesIT.assertEachBreaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.at;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.breaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.item;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreValueFormsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUALIFICATION = "{\"qualifications\": [{\"id\": \"q-1\", \"personId\": \"p-1\", "
            + "\"kindId\": \"license\", \"number\": \" 12345\", \"validFrom\": \"2020-04-01\", \"attributes\": {}}]}";

    private static String names(String names) {
        return "{\"names\": " + names + "}";
    }

    private static RestoreRulesIT.Case time(String list, String id, String at, String field, String time) {
        return breaks(list, id, field, null, at(at, "{\"" + field + "\": \"" + time + "\"}"));
    }

    // A write rejects the spaces around these values. U+3000 is the space of Japanese text.
    @Test
    void aValueWithSpacesAroundItFailsTheRestoreAndNothingIsWritten() throws Exception {
        String space = "invalid.surrounding_space";
        assertEachBreaks("restore-spaces", List.of(
                breaks("hierarchies", "h-1", "name", null, at("/hierarchies/0", "{\"name\": \" Project \"}")),
                breaks("change-orders", "o-1", "title", null, at("/changeOrders/0", "{\"title\": \"異動　\"}")),
                breaks("change-orders", "o-1", "externalReference", null,
                        at("/changeOrders/0", "{\"externalReference\": \"DOC-9\\n\"}")),
                breaks("qualifications", "q-1", "number", null, at("/refItems", item("license", "QUALIFICATION", "")),
                        at("", QUALIFICATION)),
                breaks("org-units", "u-1", "timeZone", null, at("/orgUnits/0", "{\"timeZone\": \" Asia/Tokyo \"}")),
                breaks("org-units", "u-1", "dayStart", null, at("/orgUnits/0", "{\"dayStart\": \"04:00 \"}")),
                breaks("external-ids", "x-1", "externalId", null, at("/externalIds/0", "{\"externalId\": \"H-1 \"}")),
                breaks("people", "p-1", "names.ja.name", space,
                        at("/people/0", names("{\"ja\": {\"name\": \"　山田\"}}"))),
                breaks("people", "p-1", "names.ja.phoneticName", space,
                        at("/people/0", names("{\"ja\": {\"name\": \"山田\", \"phoneticName\": \"やまだ \"}}"))),
                breaks("people", "p-1", "attributes.note", space,
                        at("/people/0", "{\"attributes\": {\"note\": \"line 1\\nline 2\\n\"}}")),
                breaks("org-unit-versions", "v-1", "names.ja.shortName", space, at("/orgUnits/0/versions/0",
                        names("{\"ja\": {\"name\": \"本社\", \"shortName\": \" 本社\"}}"))),
                breaks("ref-item-names", "n-1", "names.ja.name", space,
                        at("/refItems/0/versions/0", names("{\"ja\": {\"name\": \"承認者\\t\"}}"))),
                breaks("ref-kinds", "k-1", "names.ja.name", space,
                        at("/refKinds/0", names("{\"ja\": {\"name\": \" 拠点\"}}")))));
    }

    // primaryLanguage is the same text as a key, as in a write.
    @Test
    void aPrimaryLanguageInAnotherCaseThanItsKeyFailsTheRestoreAndNothingIsWritten() throws Exception {
        String primary = "invalid.primary_language";
        assertEachBreaks("restore-tags", List.of(
                breaks("ref-kinds", "k-1", "primaryLanguage", primary,
                        at("/refKinds/0", "{\"primaryLanguage\": \"JA\"}")),
                breaks("people", "p-1", "primaryLanguage", primary,
                        at("/people/0", names("{\"JA\": {\"name\": \"山田\"}}"))),
                breaks("org-unit-versions", "v-1", "primaryLanguage", primary, at("/orgUnits/0/versions/0",
                        "{\"primaryLanguage\": \"en-US\", \"names\": {\"en-us\": {\"name\": \"Head office\"}}}"))));
    }

    // Locale would give ja, en-US, and he.
    @Test
    void aLanguageTagComesBackAsTheDocumentHasIt() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-tag-forms");
        try {
            ObjectNode document = RestoreKeysIT.document();
            RestoreKeysIT.change(document, "/refKinds/0", "{\"primaryLanguage\": \"JA\", \"names\": "
                    + "{\"JA\": {\"name\": \"拠点\"}}}");
            RestoreKeysIT.change(document, "/people/0", "{\"primaryLanguage\": \"iw\", \"names\": "
                    + "{\"iw\": {\"name\": \"ימאדה\"}, \"ja\": {\"name\": \"山田\"}}}");
            RestoreKeysIT.change(document, "/orgUnits/0/versions/0",
                    names("{\"ja\": {\"name\": \"本社\"}, \"en-us\": {\"name\": \"Head office\"}}"));
            HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(document));
            assertEquals(200, restored.statusCode(), restored.body());

            ObjectNode export = RealmExportIT.export(target.api);
            assertEquals("JA", export.at("/refKinds/0/primaryLanguage").asText());
            assertEquals("拠点", export.at("/refKinds/0/names/JA/name").asText());
            assertEquals("iw", export.at("/people/0/primaryLanguage").asText());
            assertEquals(List.of("iw", "ja"), keys(export.at("/people/0/names")));
            JsonNode head = null;
            for (JsonNode unit : export.get("orgUnits")) {
                if (unit.get("id").asText().equals("u-1")) {
                    head = unit;
                }
            }
            assertEquals(List.of("en-us", "ja"), keys(head.at("/versions/0/names")));
        } finally {
            target.remove();
        }
    }

    private static List<String> keys(JsonNode object) {
        List<String> keys = new ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // An export gives a time in UTC with Z, and with 3 or 6 digits of a second when it has a fraction. Java reads
    // 23:59:60 as 23:59:59, and PostgreSQL rounds a time to microseconds.
    @Test
    void aTimeInAnotherFormThanAnExportGivesFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-times", List.of(
                time("change-orders", "o-1", "/changeOrders/0", "createdAt", "2024-03-01T09:00:00+09:00"),
                time("change-orders", "o-1", "/changeOrders/0", "createdAt", "2024-03-01T00:00:00.123456789Z"),
                time("change-orders", "o-1", "/changeOrders/0", "createdAt", "2024-03-01T00:00:00.12Z"),
                time("change-orders", "o-1", "/changeOrders/0", "createdAt", "2024-03-01T00:00:00.123000Z"),
                time("change-orders", "o-1", "/changeOrders/0", "announcedAt", "2024-03-01T00:00:00+00:00"),
                time("change-orders", "o-1", "/changeOrders/0", "announcedAt", "2024-02-29T24:00:00Z"),
                time("people", "p-1", "/people/0", "maskedAt", "2024-03-01t00:00:00z"),
                time("people", "p-1", "/people/0", "maskedAt", "2024-03-01T00:00:00.000Z"),
                time("erasures", "p-9", "/erasures/0", "erasedAt", "2024-03-01T23:59:60Z"),
                time("erasures", "p-9", "/erasures/0", "erasedAt", "2024-03-01T00:00:00.1234567Z")));
    }

    // A write stores the attributes without a name with null, so an export never gives one. The document defines only
    // the attribute note of a person. A name with no definition fails as in a write, also with null.
    @Test
    void aNameWithNullInTheAttributesFailsTheRestoreAndNothingIsWritten() throws Exception {
        String note = "{\"attributes\": {\"note\": null}}";
        String unitNote = "{\"id\": \"d-2\", \"target\": \"ORG_UNIT\", \"name\": \"note\", \"type\": \"STRING\"}";
        assertEachBreaks("restore-attribute-nulls", List.of(
                breaks("people", "p-1", "attributes.note", null, at("/people/0", note)),
                breaks("org-unit-versions", "v-1", "attributes.note", null, at("/attributeDefinitions", unitNote),
                        at("/orgUnits/0/versions/0", note)),
                breaks("ref-items", "role-1", "attributes.note", "attribute.unknown", at("/refItems/0", note)),
                breaks("assignments", "r-1", "attributes.note", "attribute.unknown",
                        at("/assignments/0/periods/0", note))));
    }

    @Test
    void aTimeInTheFormOfAnExportComesBackAsItWas() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-time-forms");
        try {
            ObjectNode document = RestoreKeysIT.document();
            RestoreKeysIT.change(document, "/changeOrders/0", "{\"createdAt\": \"2024-03-01T00:00:00.120Z\", "
                    + "\"announcedAt\": \"2024-03-01T00:00:00.123456Z\"}");
            RestoreKeysIT.change(document, "/people/0", "{\"maskedAt\": \"2024-03-01T00:00:00.000100Z\"}");
            HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(document));
            assertEquals(200, restored.statusCode(), restored.body());

            ObjectNode export = RealmExportIT.export(target.api);
            assertEquals(List.of("2024-03-01T00:00:00.120Z", "2024-03-01T00:00:00.123456Z",
                    "2024-03-01T00:00:00.000100Z", "2024-03-01T00:00:00Z"), List.of(
                    export.at("/changeOrders/0/createdAt").asText(), export.at("/changeOrders/0/announcedAt").asText(),
                    export.at("/people/0/maskedAt").asText(), export.at("/erasures/0/erasedAt").asText()));
        } finally {
            target.remove();
        }
    }
}

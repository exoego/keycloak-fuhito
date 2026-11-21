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

    // A write strips the spaces around these values. U+3000 is the space of Japanese text.
    @Test
    void aValueWithSpacesAroundItFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-spaces", List.of(
                breaks("hierarchies", "h-1", "name", null, at("/hierarchies/0", "{\"name\": \" Project \"}")),
                breaks("change-orders", "o-1", "title", null, at("/changeOrders/0", "{\"title\": \"異動　\"}")),
                breaks("qualifications", "q-1", "number", null, at("/refItems", item("license", "QUALIFICATION", "")),
                        at("", QUALIFICATION)),
                breaks("org-units", "u-1", "timeZone", null, at("/orgUnits/0", "{\"timeZone\": \" Asia/Tokyo \"}")),
                breaks("org-units", "u-1", "dayStart", null, at("/orgUnits/0", "{\"dayStart\": \"04:00 \"}")),
                breaks("people", "p-1", "names.ja.name", null,
                        at("/people/0", names("{\"ja\": {\"name\": \"　山田\"}}"))),
                breaks("people", "p-1", "names.ja.phoneticName", null,
                        at("/people/0", names("{\"ja\": {\"name\": \"山田\", \"phoneticName\": \"やまだ \"}}"))),
                breaks("org-unit-versions", "v-1", "names.ja.shortName", null, at("/orgUnits/0/versions/0",
                        names("{\"ja\": {\"name\": \"本社\", \"shortName\": \" 本社\"}}"))),
                breaks("ref-item-names", "n-1", "names.ja.name", null,
                        at("/refItems/0/versions/0", names("{\"ja\": {\"name\": \"承認者\\t\"}}"))),
                breaks("ref-kinds", "k-1", "names.ja.name", null,
                        at("/refKinds/0", names("{\"ja\": {\"name\": \" 拠点\"}}")))));
    }

    // A write gives a language tag in one form, such as ja for JA and en-US for en-us.
    @Test
    void aLanguageTagInAnotherFormFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-tags", List.of(
                breaks("ref-kinds", "k-1", "primaryLanguage", null, at("/refKinds/0", "{\"primaryLanguage\": \"JA\"}")),
                breaks("people", "p-1", "names.JA", null, at("/people/0", names("{\"JA\": {\"name\": \"山田\"}}"))),
                breaks("org-unit-versions", "v-1", "names.en-us", null, at("/orgUnits/0/versions/0",
                        names("{\"ja\": {\"name\": \"本社\"}, \"en-us\": {\"name\": \"Head office\"}}")))));
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
    // the attribute note of a person.
    @Test
    void aNameWithNullInTheAttributesFailsTheRestoreAndNothingIsWritten() throws Exception {
        String note = "{\"attributes\": {\"note\": null}}";
        assertEachBreaks("restore-attribute-nulls", List.of(
                breaks("people", "p-1", "attributes.note", null, at("/people/0", note)),
                breaks("ref-items", "role-1", "attributes.note", null, at("/refItems/0", note)),
                breaks("org-unit-versions", "v-1", "attributes.note", null, at("/orgUnits/0/versions/0", note)),
                breaks("employment-terms", "t-1", "attributes.note", null, at("/employments/0/terms/0", note)),
                breaks("assignments", "r-1", "attributes.note", null, at("/assignments/0/periods/0", note))));
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

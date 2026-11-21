package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreRulesIT.assertEachBreaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.at;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.breaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.item;

class RestoreValueFormsIT {

    private static final String QUALIFICATION = "{\"qualifications\": [{\"id\": \"q-1\", \"personId\": \"p-1\", "
            + "\"kindId\": \"license\", \"number\": \" 12345\", \"validFrom\": \"2020-04-01\", \"attributes\": {}}]}";

    private static String names(String names) {
        return "{\"names\": " + names + "}";
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
}

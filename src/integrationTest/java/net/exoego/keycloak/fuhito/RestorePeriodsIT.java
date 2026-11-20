package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.RestoreMissingValuesIT.assertBadValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestorePeriodsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A row with a period.
     *
     * @param at the JSON pointer of the row
     * @param list the list of the error
     * @param id the ID of the row
     */
    private record Row(String at, String list, String id) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("/refItems/0/versions/0", "ref-item-names", "n-1"),
            new Row("/orgUnits/0/versions/0", "org-unit-versions", "v-1"),
            new Row("/orgUnits/1/parents/0", "org-unit-parents", "l-1"),
            new Row("/employments/0", "employments", "e-1"),
            new Row("/employments/0/terms/0", "employment-terms", "t-1"),
            new Row("/assignments/0/periods/0", "assignments", "r-1"),
            new Row("/reportingLines/0", "reporting-lines", "rl-1"),
            new Row("/unitRoles/0", "unit-roles", "ur-1"),
            new Row("/qualifications/0", "qualifications", "q-1"));

    // The document of RestoreKeysIT with the lists that it does not have.
    private static ObjectNode document() throws Exception {
        ObjectNode document = RestoreKeysIT.document();
        ((ArrayNode) document.get("refItems")).add(JSON.readTree("""
                {"id": "license", "kind": "QUALIFICATION", "code": "license", "attributes": {},
                 "versions": [{"id": "n-2", "primaryLanguage": "ja", "names": {"ja": {"name": "免許"}}}]}
                """));
        ((ArrayNode) document.get("people")).add(JSON.readTree("{\"id\": \"p-2\", \"attributes\": {}}"));
        document.set("reportingLines", JSON.readTree("""
                [{"id": "rl-1", "assignmentId": "a-1", "managerPersonId": "p-2", "kind": "SOLID",
                  "validFrom": "2020-04-01"}]
                """));
        document.set("qualifications", JSON.readTree("""
                [{"id": "q-1", "personId": "p-1", "kindId": "license", "validFrom": "2020-04-01", "attributes": {}}]
                """));
        return document;
    }

    @Test
    void aPeriodThatDoesNotEndAfterItStartsFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-periods");
        try {
            // validTo is the first date after a period, so a period that ends on its start has no date.
            assertAll(Stream.of("2019-04-01", "2020-04-01").flatMap(validTo -> ROWS.stream()
                    .map(row -> (Executable) () -> {
                        ObjectNode document = document();
                        ((ObjectNode) document.at(row.at())).put("validFrom", "2020-04-01").put("validTo", validTo);
                        assertBadValue(target.api, document, Map.of("list", row.list(), "id", row.id(), "field",
                                "validTo"));
                    })));
            // Each case wrote nothing, so the document restores into the realm.
            HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(document()));
            assertEquals(200, restored.statusCode(), restored.body());
        } finally {
            target.remove();
        }
    }
}

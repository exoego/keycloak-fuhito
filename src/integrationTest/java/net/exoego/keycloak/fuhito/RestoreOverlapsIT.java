package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.exoego.keycloak.fuhito.RestoreKeysIT.Case;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreKeysIT.assertEachFails;
import static net.exoego.keycloak.fuhito.RestoreKeysIT.target;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreOverlapsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}";

    @Test
    void twoRowsThatMustNotShareADateFailTheRestoreAndNothingIsWritten() throws Exception {
        assertEachFails("restore-overlaps", "restore.overlap", List.of(
                new Case("/orgUnits/0/versions", "{\"id\": \"v-3\", \"validFrom\": \"2021-04-01\", " + NAMES
                        + ", \"attributes\": {}}", "org-unit-versions", "v-3", "validFrom", "v-1"),
                new Case("/orgUnits/1/versions/0", "{\"code\": \"U1\"}", "org-unit-versions", "v-2", "code", "v-1"),
                new Case("/orgUnits/1/parents", "{\"id\": \"l-2\", \"hierarchy\": \"default\", \"parentId\": \"u-1\", "
                        + "\"validFrom\": \"2021-04-01\"}", "org-unit-parents", "l-2", "hierarchy", "l-1"),
                new Case("/employments/0/terms", "{\"id\": \"t-2\", \"validFrom\": \"2021-04-01\", "
                        + "\"status\": \"ON_LEAVE\", \"attributes\": {}}", "employment-terms", "t-2", "validFrom", "t-1"),
                new Case("/assignments/0/periods", "{\"rowId\": \"r-2\", \"employmentId\": \"e-1\", "
                        + "\"orgUnitId\": \"u-2\", \"validFrom\": \"2021-04-01\", \"attributes\": {}}",
                        "assignments", "r-2", "validFrom", "r-1"),
                new Case("/assignments", "{\"assignmentId\": \"a-2\", \"periods\": [{\"rowId\": \"r-3\", "
                        + "\"employmentId\": \"e-1\", \"orgUnitId\": \"u-2\", \"validFrom\": \"2020-04-01\", "
                        + "\"primary\": true, \"attributes\": {}}]}", "assignments", "r-3", "primary", "r-1"),
                new Case("/unitRoles", "{\"id\": \"ur-2\", \"employmentId\": \"e-1\", \"orgUnitId\": \"u-1\", "
                        + "\"roleId\": \"role-1\", \"validFrom\": \"2021-04-01\"}", "unit-roles", "ur-2", "roleId", "ur-1"),
                // The first names of the item hold from the start.
                new Case("/refItems/0/versions", "{\"id\": \"n-2\", \"validFrom\": \"2021-04-01\", " + NAMES + "}",
                        "ref-item-names", "n-2", "validFrom", "n-1")));
    }

    @Test
    void anAssignmentIdOfAnotherRealmIsTaken() throws Exception {
        TestRealm first = target("restore-assignment");
        TestRealm second = TestRealm.create("restore-assignment-copy");
        try {
            assertEquals(200, first.api.post("/restore", JSON.writeValueAsString(RestoreKeysIT.document()))
                    .statusCode());
            assertTaken(second);
        } finally {
            first.remove();
            second.remove();
        }
    }

    private static void assertTaken(TestRealm second) throws Exception {
        // Every row has another ID, but the assignment has the ID of the one of the first realm.
        ObjectNode copy = (ObjectNode) JSON.readTree("""
                {"format": "fuhito.export.v1",
                 "refItems": [{"id": "corp-b", "kind": "UNIT_TYPE", "code": "corp", "legalEntity": true,
                   "attributes": {}, "versions": [{"id": "n-b", "primaryLanguage": "ja",
                                                   "names": {"ja": {"name": "法人"}}}]}],
                 "orgUnits": [{"id": "u-b", "parents": [], "successors": [],
                   "versions": [{"id": "v-b", "validFrom": "2020-04-01", "unitTypeId": "corp-b",
                                 "primaryLanguage": "ja", "names": {"ja": {"name": "本社"}}, "attributes": {}}]}],
                 "people": [{"id": "p-b", "attributes": {}}],
                 "employments": [{"id": "e-b", "personId": "p-b", "employerId": "u-b", "validFrom": "2020-04-01",
                                  "terms": [{"id": "t-b", "validFrom": "2020-04-01", "status": "ACTIVE",
                                             "attributes": {}}]}],
                 "assignments": [{"assignmentId": "a-1", "periods": [{"rowId": "r-b", "employmentId": "e-b",
                                  "orgUnitId": "u-b", "validFrom": "2020-04-01", "attributes": {}}]}]}
                """);

        HttpResponse<String> response = second.api.post("/restore", JSON.writeValueAsString(copy));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("restore.id_taken", Api.json(response).get("error").asText());
        assertEquals(JSON.valueToTree(Map.of("list", "assignments", "id", "a-1")), Api.json(response).get("params"));
        assertEquals(0, Api.json(second.api.get("/export/people")).size());
    }
}

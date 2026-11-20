package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreKeysIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Every list with one row, and rows that the cases add to or change.
    private static final String DOCUMENT = """
            {"format": "fuhito.export.v1",
             "refKinds": [{"id": "k-1", "code": "site", "primaryLanguage": "ja", "names": {"ja": {"name": "拠点"}}}],
             "refItems": [{"id": "role-1", "kind": "UNIT_ROLE", "code": "approver", "attributes": {},
                           "versions": [{"id": "n-1", "primaryLanguage": "ja", "names": {"ja": {"name": "承認者"}}}]},
                          {"id": "corp", "kind": "UNIT_TYPE", "code": "corp", "legalEntity": true, "attributes": {},
                           "versions": [{"id": "n-c", "primaryLanguage": "ja", "names": {"ja": {"name": "法人"}}}]}],
             "attributeDefinitions": [{"id": "d-1", "target": "PERSON", "name": "note", "type": "STRING"}],
             "hierarchies": [{"id": "h-1", "code": "project", "name": "Project"}],
             "orgUnits": [
               {"id": "u-1", "organizationAlias": "x-hospital", "parents": [], "successors": [],
                "versions": [{"id": "v-1", "validFrom": "2020-04-01", "code": "U1", "unitTypeId": "corp",
                              "primaryLanguage": "ja", "names": {"ja": {"name": "本社"}}, "attributes": {}}]},
               {"id": "u-2", "successors": [],
                "versions": [{"id": "v-2", "validFrom": "2020-04-01", "code": "U2", "primaryLanguage": "ja",
                              "names": {"ja": {"name": "病棟"}}, "attributes": {}}],
                "parents": [{"id": "l-1", "hierarchy": "default", "parentId": "u-1", "validFrom": "2020-04-01"}]},
               {"id": "u-3", "parents": [],
                "versions": [{"id": "v-9", "validFrom": "2020-04-01", "validTo": "2021-04-01", "primaryLanguage": "ja",
                              "names": {"ja": {"name": "旧病棟"}}, "attributes": {}}],
                "successors": [{"id": "s-1", "successorId": "u-2", "effectiveDate": "2021-04-01"}]}],
             "people": [{"id": "p-1", "username": "nurse", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}},
                         "attributes": {}}],
             "employments": [{"id": "e-1", "personId": "p-1", "employerId": "u-1", "validFrom": "2020-04-01",
                              "terms": [{"id": "t-1", "validFrom": "2020-04-01", "status": "ACTIVE", "attributes": {}}]}],
             "assignments": [{"assignmentId": "a-1", "periods": [{"rowId": "r-1", "employmentId": "e-1",
                              "orgUnitId": "u-1", "validFrom": "2020-04-01", "primary": true, "attributes": {}}]}],
             "unitRoles": [{"id": "ur-1", "employmentId": "e-1", "orgUnitId": "u-1", "roleId": "role-1",
                            "validFrom": "2020-04-01"}],
             "externalIds": [{"id": "x-1", "resourceType": "ORG_UNIT", "resourceId": "u-1", "source": "hr",
                              "externalId": "U-1"}],
             "changeOrders": [{"id": "o-1", "number": "N-1", "title": "異動", "effectiveDate": "2024-04-01",
                               "createdAt": "2024-03-01T00:00:00Z"}],
             "erasures": [{"personId": "p-9", "erasedAt": "2024-03-01T00:00:00Z"}]}
            """;

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}";

    private static final String HIERARCHY =
            "insert into fuhito_hierarchy (id, realm_id, code, name) values ('h-1', ?, 'race', 'Race')";

    /**
     * A change of the document and the error that it gives.
     *
     * @param at the JSON pointer of a list, which gets the row, or of a row, which gets its fields
     * @param row the row or the fields
     * @param list the list of the error
     * @param id the ID of the row of the error
     * @param field the field of the error
     * @param other the ID of the other row of the error
     */
    record Case(String at, String row, String list, String id, String field, String other) {
    }

    /** Makes the realm that the document needs. */
    static TestRealm target(String prefix) {
        TestRealm target = TestRealm.create(prefix);
        target.user("nurse");
        target.organization("x-hospital");
        return target;
    }

    static ObjectNode document() throws Exception {
        return (ObjectNode) JSON.readTree(DOCUMENT);
    }

    static ObjectNode changed(Case change) throws Exception {
        ObjectNode document = document();
        change(document, change.at(), change.row());
        return document;
    }

    /**
     * Changes a document.
     *
     * @param document the document
     * @param pointer the JSON pointer of a list, which gets the row, or of a row, which gets its fields
     * @param row the row or the fields
     */
    static void change(ObjectNode document, String pointer, String row) throws Exception {
        JsonNode at = document.at(pointer);
        if (at.isArray()) {
            ((ArrayNode) at).add(JSON.readTree(row));
        } else {
            ((ObjectNode) at).setAll((ObjectNode) JSON.readTree(row));
        }
    }

    // Each case fails before anything is written. So the document restores into the realm at the end. The IDs of the
    // document are free again once the realm is gone.
    static void assertEachFails(String prefix, String code, List<Case> cases) throws Exception {
        TestRealm target = target(prefix);
        try {
            assertAll(cases.stream().map(change -> (Executable) () -> {
                HttpResponse<String> response = target.api.post("/restore",
                        JSON.writeValueAsString(changed(change)));
                assertEquals(400, response.statusCode(), change + " " + response.body());
                JsonNode error = Api.json(response);
                assertEquals(code, error.get("error").asText(), response.body());
                assertEquals(JSON.valueToTree(Map.of("list", change.list(), "id", change.id(), "field",
                        change.field(), "other", change.other())), error.get("params"), response.body());
            }));
            HttpResponse<String> restored = target.api.post("/restore", DOCUMENT);
            assertEquals(200, restored.statusCode(), restored.body());
        } finally {
            target.remove();
        }
    }

    @Test
    void twoRowsWithTheSameKeyFailTheRestoreAndNothingIsWritten() throws Exception {
        assertEachFails("restore-keys", "restore.duplicate", List.of(
                new Case("/people", "{\"id\": \"p-1\", " + NAMES + ", \"attributes\": {}}",
                        "people", "p-1", "id", "p-1"),
                new Case("/orgUnits/1/versions", "{\"id\": \"v-1\", \"validFrom\": \"2021-04-01\", " + NAMES
                        + ", \"attributes\": {}}", "org-unit-versions", "v-1", "id", "v-1"),
                new Case("/hierarchies", "{\"id\": \"h-2\", \"code\": \"project\", \"name\": \"Other\"}",
                        "hierarchies", "h-2", "code", "h-1"),
                new Case("/changeOrders", "{\"id\": \"o-2\", \"number\": \"N-1\", \"title\": \"X\", "
                        + "\"effectiveDate\": \"2024-04-01\", \"createdAt\": \"2024-03-01T00:00:00Z\"}",
                        "change-orders", "o-2", "number", "o-1"),
                // Keycloak finds a user by a username in any case.
                new Case("/people", "{\"id\": \"p-2\", \"username\": \"NURSE\", " + NAMES + ", \"attributes\": {}}",
                        "people", "p-2", "username", "p-1"),
                new Case("/refKinds", "{\"id\": \"k-2\", \"code\": \"site\", " + NAMES + "}",
                        "ref-kinds", "k-2", "code", "k-1"),
                new Case("/refItems", "{\"id\": \"role-2\", \"kind\": \"UNIT_ROLE\", \"code\": \"approver\", "
                        + "\"attributes\": {}, \"versions\": [{\"id\": \"n-2\", " + NAMES + "}]}",
                        "ref-items", "role-2", "code", "role-1"),
                new Case("/attributeDefinitions", "{\"id\": \"d-2\", \"target\": \"PERSON\", \"name\": \"note\", "
                        + "\"type\": \"STRING\"}", "attribute-definitions", "d-2", "name", "d-1"),
                new Case("/orgUnits/2/successors", "{\"id\": \"s-2\", \"successorId\": \"u-2\", "
                        + "\"effectiveDate\": \"2022-04-01\"}", "org-unit-successions", "s-2", "successorId", "s-1"),
                new Case("/externalIds", "{\"id\": \"x-2\", \"resourceType\": \"ORG_UNIT\", \"resourceId\": \"u-2\", "
                        + "\"source\": \"hr\", \"externalId\": \"U-1\"}", "external-ids", "x-2", "externalId", "x-1"),
                new Case("/externalIds", "{\"id\": \"x-2\", \"resourceType\": \"ORG_UNIT\", \"resourceId\": \"u-1\", "
                        + "\"source\": \"hr\", \"externalId\": \"U-9\"}", "external-ids", "x-2", "source", "x-1"),
                new Case("/orgUnits/1", "{\"organizationAlias\": \"x-hospital\"}",
                        "org-units", "u-2", "organizationAlias", "u-1"),
                new Case("/erasures", "{\"personId\": \"p-9\", \"erasedAt\": \"2024-05-01T00:00:00Z\"}",
                        "erasures", "p-9", "personId", "p-9")));
    }

    @Test
    void aRowThatAnotherWriteStoresFirstFailsWithTheNameOfTheRule() throws Exception {
        TestRealm target = target("restore-race");
        try {
            CompletableFuture<HttpResponse<String>> restore;
            try (Connection other = KeycloakEnvironment.connection();
                    PreparedStatement insert = other.prepareStatement(HIERARCHY)) {
                other.setAutoCommit(false);
                insert.setString(1, target.id());
                insert.executeUpdate();
                // The restore finds no row with the ID, and then waits for the other write to end.
                restore = waitingFor(other, () -> target.api.post("/restore", DOCUMENT));
                other.commit();
            }
            HttpResponse<String> response = restore.get(1, TimeUnit.MINUTES);

            assertEquals(409, response.statusCode(), response.body());
            assertEquals("restore.conflict", Api.json(response).get("error").asText());
            assertEquals(JSON.valueToTree(Map.of("constraint", "PK_FUHITO_HIERARCHY")),
                    Api.json(response).get("params"));
            assertEquals(0, Api.json(target.api.get("/export/people")).size());
        } finally {
            target.remove();
        }
    }
}

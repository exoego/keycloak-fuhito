package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The IDs of other systems of a person whose data is erased.
class ErasedExternalIdsIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    // A restore erases the person of the record again, so the IDs of the export go too. Each record has the ID of
    // the record as its ID in the other system.
    @Test
    void anEraseAndARestoreRemoveTheIdsOfEveryRecordOfThePersonAndKeepThoseOfOthers() throws Exception {
        TestRealm realm = TestRealm.withEmployer("erased-external-ids");
        Api api = realm.api;
        String unit = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        List<String> users = List.of("erased", "other", "boss");
        Map<String, String> userIds = new HashMap<>();
        users.forEach(name -> userIds.put(name, realm.user(name)));
        Map<String, List<String[]>> records = new HashMap<>();
        for (String name : List.of("erased", "other")) {
            HttpResponse<String> response = api.post("/staff", "{\"userId\": \"" + userIds.get(name)
                    + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\"}");
            assertEquals(201, response.statusCode(), response.body());
            JsonNode staff = Api.json(response);
            records.put(name, new ArrayList<>(List.of(
                    new String[] {"PERSON", staff.get("employment").get("personId").asText()},
                    new String[] {"EMPLOYMENT", staff.get("employment").get("id").asText()},
                    new String[] {"ASSIGNMENT", staff.get("assignment").get("id").asText()})));
        }
        List<String[]> erased = records.get("erased");
        List<String[]> other = records.get("other");
        String from = "\", \"validFrom\": \"2020-04-01\"}";
        erased.add(new String[] {"QUALIFICATION", id(api.post("/qualifications", "{\"userId\": \""
                + userIds.get("erased") + "\", \"kind\": \"license" + from))});
        erased.add(new String[] {"UNIT_ROLE", id(api.post("/unit-roles", "{\"employmentId\": \"" + erased.get(1)[1]
                + "\", \"orgUnitId\": \"" + unit + "\", \"role\": \"approver" + from))});
        // A line where the person reports, and a line where the person is the manager.
        erased.add(new String[] {"REPORTING_LINE", id(api.post("/reporting-lines", "{\"assignmentId\": \""
                + erased.get(2)[1] + "\", \"managerUserId\": \"" + userIds.get("boss") + from))});
        erased.add(new String[] {"REPORTING_LINE", id(api.post("/reporting-lines", "{\"assignmentId\": \""
                + other.get(2)[1] + "\", \"managerUserId\": \"" + userIds.get("erased") + from))});
        for (List<String[]> list : records.values()) {
            for (String[] record : list) {
                ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"" + record[0] + "\", \"resourceId\": \""
                        + record[1] + "\", \"source\": \"hr\", \"externalId\": \"" + record[1] + "\"}"));
            }
        }
        ObjectNode before = RealmExportIT.export(api);

        assertEquals(204, api.deleteWithVersion("/people/" + erased.get(0)[1] + "/data").statusCode());
        assertIds(api, erased, other);

        before.set("erasures", RealmExportIT.export(api).get("erasures"));
        TestRealm target = TestRealm.create("erased-external-ids-target");
        users.forEach(target::user);
        realm.remove();
        ok(target.api.post("/restore", before.toString()));
        assertIds(target.api, erased, other);
    }

    private static void assertIds(Api api, List<String[]> gone, List<String[]> kept) throws Exception {
        String lookup = "/external-ids/lookup?resourceType=%s&source=hr&externalId=%s";
        for (String[] record : gone) {
            HttpResponse<String> response = api.get(lookup.formatted(record[0], record[1]));
            assertEquals(404, response.statusCode(), response.body());
            assertEquals("external_id.unknown", Api.json(response).get("error").asText());
        }
        for (String[] record : kept) {
            assertEquals(record[1], ok(api.get(lookup.formatted(record[0], record[1]))).get("resourceId").asText());
        }
    }
}

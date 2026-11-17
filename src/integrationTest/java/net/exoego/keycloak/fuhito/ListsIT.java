package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListsIT {

    private static List<String> follow(Api api, String path, String field) throws Exception {
        List<String> values = new ArrayList<>();
        String next = path;
        while (next != null) {
            HttpResponse<String> response = api.get(next);
            assertEquals(200, response.statusCode(), response.body());
            values.addAll(Api.json(response).findValuesAsText(field));
            next = response.headers().firstValue("Link")
                    .map(link -> link.substring(link.indexOf("/fuhito") + "/fuhito".length(), link.indexOf('>')))
                    .orElse(null);
        }
        return values;
    }

    @Test
    void listsEmploymentsAssignmentsAndQualificationsAcrossTheRealm() throws Exception {
        TestRealm realm = TestRealm.withEmployer("lists");
        Api api = realm.api;
        realm.refItem("JOB_TITLE", "chief", "主任");
        realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("ASSIGNMENT_KIND", "regular", "本務");
        String ward = Api.json(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String user = realm.user("nurse-" + i);
            users.add(user);
            HttpResponse<String> staff = api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + ward
                    + "\", \"validFrom\": \"2026-04-01\", \"staffNumber\": \"N-" + i + "\""
                    + (i == 0 ? ", \"jobTitle\": \"chief\"" : "") + "}");
            assertEquals(201, staff.statusCode(), staff.body());
            assertEquals(201, api.post("/qualifications", "{\"userId\": \"" + user + "\", \"kind\": \"license\", "
                    + "\"validFrom\": \"2020-04-01\"}").statusCode());
        }

        assertEquals(List.of("N-0", "N-1", "N-2"), follow(api, "/employments?max=2&asOf=2026-05-01", "staffNumber")
                .stream().sorted().toList());
        assertEquals(List.of("N-1"), Api.json(api.get("/employments?staffNumber=N-1")).findValuesAsText("staffNumber"));
        assertEquals(3, api.count("/employments/count"));
        assertEquals(1, api.count("/employments/count?staffNumber=N-1"));

        assertEquals(List.of(users.get(0)), Api.json(api.get("/assignments?jobTitle=chief&asOf=2026-05-01"))
                .findValuesAsText("userId"));
        assertEquals(3, Api.json(api.get("/assignments?orgUnitId=" + ward + "&asOf=2026-05-01")).size());
        assertEquals(0, Api.json(api.get("/assignments?occupation=nurse&kind=regular&asOf=2026-05-01")).size());
        assertEquals(0, Api.json(api.get("/employments?staffNumber=none")).size());
        String employer = Api.json(api.get("/employments?staffNumber=N-0")).get(0).get("employerId").asText();
        assertEquals(3, Api.json(api.get("/employments?employerId=" + employer)).size());
        assertEquals(3, follow(api, "/qualifications?max=2", "id").size());
        assertEquals(3, follow(api, "/assignments?max=2&asOf=2026-05-01", "assignmentId").size());
        assertEquals(3, api.count("/assignments/count?asOf=2026-05-01"));
        assertEquals(1, api.count("/assignments/count?jobTitle=chief&orgUnitId=" + ward + "&asOf=2026-05-01"));
        assertEquals(400, api.get("/assignments/count?kind=none").statusCode());

        JsonNode licenses = Api.json(api.get("/qualifications?kind=license&asOf=2026-05-01"));
        assertEquals(3, licenses.size());
        assertEquals(3, api.count("/qualifications/count?kind=license"));
        assertEquals(0, api.count("/qualifications/count?kind=license&asOf=2019-04-01"));
        assertEquals(400, api.get("/qualifications?first=1&after=qualifications.v1__x").statusCode());
    }
}

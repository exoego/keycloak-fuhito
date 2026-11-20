package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A field that means something only with another field fails when the other field is missing, and the write
 * writes nothing.
 */
class UnusedFieldsIT {

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String unit(Api api, String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}, \"validFrom\": \"2026-04-01\"" + parent + "}"));
    }

    // The status, the code, and the values of the error, so that one assertion shows every case.
    private static String outcome(HttpResponse<String> response, String... params) throws Exception {
        JsonNode body = Api.json(response);
        StringBuilder outcome = new StringBuilder().append(response.statusCode()).append(' ')
                .append(body.path("error").asText());
        for (String param : params) {
            outcome.append(' ').append(body.path("params").path(param).asText());
        }
        return outcome.toString();
    }

    @Test
    void aPromotionWithAnAssignmentAndNoJobTitleOrHeadFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unused-promotion");
        Api api = realm.api;
        realm.refItem("GRADE", "g1", "1級");
        realm.refItem("GRADE", "g2", "2級");
        realm.refItem("JOB_TITLE", "chief", "主任");
        String ward = unit(api, "病棟", null);
        JsonNode nurse = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2026-04-01\", \"grade\": \"g1\"}"));
        String employment = nurse.get("employment").get("id").asText();
        String own = nurse.get("assignment").get("id").asText();
        String ofOther = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("other") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2026-04-01\"}")).get("assignment").get("id").asText();
        String path = "/employments/" + employment + "/promotion";
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (String assignmentId : List.of(own, ofOther, "nope")) {
            for (String grade : List.of(", \"grade\": \"g2\"", "")) {
                expected.add(assignmentId + grade + " 400 employment.promotion_assignment_unused " + assignmentId);
                got.add(assignmentId + grade + " " + outcome(api.postWithVersion(path, "{\"validFrom\": "
                        + "\"2026-10-01\", \"assignmentId\": \"" + assignmentId + "\"" + grade + "}"), "assignmentId"));
            }
            // With a job title, the assignment must be one of the employment on the date.
            if (!assignmentId.equals(own)) {
                expected.add(assignmentId + " chief 400 employment.assignment_not_on " + assignmentId);
                got.add(assignmentId + " chief " + outcome(api.postWithVersion(path, "{\"validFrom\": \"2026-10-01\", "
                        + "\"grade\": \"g2\", \"jobTitle\": \"chief\", \"assignmentId\": \"" + assignmentId + "\"}"),
                        "assignmentId"));
            }
        }
        assertEquals(expected, got);
        String body = "{\"validFrom\": \"2026-10-01\", \"grade\": \"g2\", \"assignmentId\": \"" + own + "\"}";
        assertEquals("The assignment \"" + own + "\" in assignmentId needs jobTitle or head in the same promotion.",
                Api.json(api.postWithVersion(path, body)).get("message").asText());
        assertEquals("assignmentId の所属「" + own + "」は、同じ昇進で jobTitle か head を指定するときだけ指定できます。",
                Api.json(api.withAcceptLanguage("ja").postWithVersion(path, body)).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        HttpResponse<String> promoted = api.postWithVersion(path, "{\"validFrom\": \"2026-10-01\", \"grade\": \"g2\", "
                + "\"jobTitle\": \"chief\", \"assignmentId\": \"" + own + "\"}");
        assertEquals(200, promoted.statusCode(), promoted.body());
        assertEquals("g2", Api.json(promoted).get("employment").get("terms").get(1).get("grade").asText());
        assertEquals(own, Api.json(promoted).get("assignment").get("id").asText());
        assertEquals("chief", Api.json(promoted).get("assignment").get("periods").get(1).get("jobTitle").asText());
        HttpResponse<String> onlyGrade = api.postWithVersion(path,
                "{\"validFrom\": \"2027-04-01\", \"grade\": \"g1\"}");
        assertEquals(200, onlyGrade.statusCode(), onlyGrade.body());
        assertEquals(3, Api.json(onlyGrade).get("employment").get("terms").size());
        assertFalse(Api.json(onlyGrade).has("assignment"));
    }

    @Test
    void aSwitchOfAnAssignmentThatIsNotPrimaryFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unused-switch");
        Api api = realm.api;
        String ward = unit(api, "病棟", null);
        String clinic = unit(api, "外来", null);
        String office = unit(api, "事務室", null);
        String employment = created(api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String start = "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"";
        String main = created(api.post("/assignments", start + ward + "\", \"validFrom\": \"2026-04-01\", "
                + "\"primary\": true}"));
        String side = created(api.post("/assignments", start + clinic + "\", \"validFrom\": \"2026-04-01\"}"));
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (String primary : List.of("", ", \"primary\": false")) {
            expected.add("add" + primary + " 400 assignment.switch_primary_unused");
            got.add("add" + primary + " " + outcome(api.post("/assignments", start + office + "\", \"validFrom\": "
                    + "\"2027-04-01\", \"switchPrimary\": true" + primary + "}")));
            expected.add("change" + primary + " 400 assignment.switch_primary_unused");
            got.add("change" + primary + " " + outcome(api.putWithVersion("/assignments/" + side + "/details",
                    "{\"validFrom\": \"2027-04-01\", \"switchPrimary\": true" + primary + "}")));
        }
        assertEquals(expected, got);
        String body = start + office + "\", \"validFrom\": \"2027-04-01\", \"switchPrimary\": true}";
        assertEquals("switchPrimary needs primary true. Only a primary assignment takes the flag from other "
                + "assignments.", Api.json(api.post("/assignments", body)).get("message").asText());
        assertEquals("switchPrimary は primary が true のときだけ指定できます。主務の所属だけがほかの所属から主務のフラグを取ります。",
                Api.json(api.withAcceptLanguage("ja").post("/assignments", body)).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        // False needs no primary flag, and true with it takes the flag.
        created(api.post("/assignments", start + office + "\", \"validFrom\": \"2027-04-01\", "
                + "\"switchPrimary\": false}"));
        HttpResponse<String> switched = api.putWithVersion("/assignments/" + side + "/details",
                "{\"validFrom\": \"2027-04-01\", \"primary\": true, \"switchPrimary\": true}");
        assertEquals(200, switched.statusCode(), switched.body());
        assertEquals(List.of("true", "false"),
                Api.json(api.get("/assignments/" + main)).get("periods").findValuesAsText("primary"));
    }

    @Test
    void aReorganizationStepWithAFieldThatItsOperationDoesNotReadFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.create("unused-reorganization").api;
        String root = unit(api, "病院", null);
        String name = "\"names\": {\"ja\": {\"name\": \"新病棟\"}}";
        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("create id", "{\"op\": \"create\", \"id\": \"%1$s\", \"primaryLanguage\": \"ja\", " + name + "}");
        steps.put("change ref", "{\"op\": \"change\", \"ref\": \"x\", \"id\": \"%1$s\", \"primaryLanguage\": \"ja\", "
                + name + "}");
        steps.put("change parentId", "{\"op\": \"change\", \"id\": \"%1$s\", \"primaryLanguage\": \"ja\", " + name
                + ", \"parentId\": \"%2$s\"}");
        steps.put("move names", "{\"op\": \"move\", \"id\": \"%1$s\", \"parentId\": \"%2$s\", " + name + "}");
        steps.put("move displayOrder", "{\"op\": \"move\", \"id\": \"%1$s\", \"parentId\": \"%2$s\", "
                + "\"displayOrder\": 1}");
        steps.put("move version", "{\"op\": \"move\", \"unitRef\": \"new\", \"version\": 1, \"parentId\": \"%2$s\"}");
        steps.put("end ref", "{\"op\": \"end\", \"ref\": \"gone\", \"id\": \"%1$s\"}");
        steps.put("end successorIds", "{\"op\": \"end\", \"id\": \"%1$s\", \"successorIds\": [\"%2$s\"]}");
        steps.put("succeed code", "{\"op\": \"succeed\", \"id\": \"%1$s\", \"successorIds\": [\"%2$s\"], "
                + "\"code\": \"x\"}");
        // Each case has units of its own, so that a step that ran does not change another case.
        Map<String, String[]> units = new LinkedHashMap<>();
        for (String step : steps.keySet()) {
            units.put(step, new String[] {unit(api, step + " A", root), unit(api, step + " B", root)});
        }
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (Map.Entry<String, String> step : steps.entrySet()) {
            String[] own = units.get(step.getKey());
            String[] opAndField = step.getKey().split(" ", -1);
            HttpResponse<String> response = api.postWithVersion("/reorganizations", "{\"effectiveDate\": "
                    + "\"2027-04-01\", \"steps\": [{\"op\": \"create\", \"ref\": \"new\", \"primaryLanguage\": \"ja\", "
                    + "\"names\": {\"ja\": {\"name\": \"新設\"}}, \"parentId\": \"" + root + "\"}, "
                    + step.getValue().formatted(own[0], own[1]) + "]}");
            expected.add(step.getKey() + " 400 reorganization.field_unused " + opAndField[1] + " " + opAndField[0]
                    + " 1");
            got.add(step.getKey() + " " + outcome(response, "field", "op", "step"));
        }
        assertEquals(expected, got);
        String body = "{\"effectiveDate\": \"2027-04-01\", \"steps\": [" + steps.get("end successorIds")
                .formatted((Object[]) units.get("end successorIds")) + "]}";
        assertEquals("This end step does not use successorIds. Leave it out.",
                Api.json(api.postWithVersion("/reorganizations", body)).get("message").asText());
        assertEquals("この end ステップは successorIds を使いません。指定しないでください。",
                Api.json(api.withAcceptLanguage("ja").postWithVersion("/reorganizations", body)).get("message")
                        .asText());
        assertEquals(before, RealmExportIT.export(api));

        HttpResponse<String> done = api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"2027-04-01\", "
                + "\"steps\": [{\"op\": \"succeed\", \"id\": \"" + units.get("end successorIds")[0]
                + "\", \"successorIds\": [\"" + units.get("end successorIds")[1] + "\"]}]}");
        assertEquals(200, done.statusCode(), done.body());
    }
}

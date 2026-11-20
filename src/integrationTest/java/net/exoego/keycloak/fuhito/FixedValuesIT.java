package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write of a stored resource that gives another value of a field that never changes, or of a field that only a
 * response fills, fails and writes nothing. The value of a read still works, and so does a write that leaves the
 * field out.
 */
class FixedValuesIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}";

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static ObjectNode read(Api api, String path) throws Exception {
        return ExternalIdsIT.read(api, path).deepCopy();
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

    // Puts the read of each path with the values of its case, and expects that each write fails and names the first
    // field of the case.
    private static void eachFails(Api api, List<List<String>> cases) throws Exception {
        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (List<String> fieldCase : cases) {
            String path = fieldCase.get(0);
            String field = fieldCase.get(1);
            ObjectNode body = read(api, path).put(field, fieldCase.get(2));
            for (int i = 3; i < fieldCase.size(); i += 2) {
                body.put(fieldCase.get(i), fieldCase.get(i + 1));
            }
            String name = String.join(" ", fieldCase);
            expected.add(name + " 400 invalid.value_fixed " + field);
            got.add(name + " " + outcome(api.put(path, body.toString()), "field"));
        }
        assertEquals(expected, got);
    }

    // A write of the read works, and so does one that leaves out the fields that never change.
    private static void readAsBodyWorks(Api api, String path, String... fields) throws Exception {
        ok(api.put(path, read(api, path).toString()));
        ok(api.put(path, read(api, path).without(List.of(fields)).toString()));
    }

    @Test
    void aPutWithAnotherCodeOrPersonFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("fixed-values");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        for (String kind : List.of("site", "area")) {
            created(api.post("/ref-kinds", "{\"code\": \"" + kind + "\", " + NAMES + "}"));
        }
        String item = created(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", " + NAMES + "}"));
        String definition = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"}"));
        String hierarchy = created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String nurse = realm.user("nurse");
        String person = created(api.post("/people", "{\"userId\": \"" + nurse + "\"}"));
        String doctor = realm.user("doctor");
        String other = created(api.post("/people", "{\"userId\": \"" + doctor + "\"}"));
        String plain = created(api.post("/people", "{" + NAMES + "}"));
        // The user has no person, so a write that used the user would create one.
        String nobody = realm.user("nobody");
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        String ofPlain = created(api.post("/qualifications", "{\"personId\": \"" + plain + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        String ofQualification = "/qualifications/" + qualification;
        ObjectNode before = RealmExportIT.export(api);

        eachFails(api, List.of(
                List.of("/ref-kinds/site", "code", "area"),
                List.of("/hierarchies/" + hierarchy, "code", "default"),
                List.of("/ref-items/" + item, "kind", "area"),
                List.of("/ref-items/" + item, "code", "osaka"),
                List.of("/attribute-definitions/" + definition, "target", "ORG_UNIT"),
                List.of("/attribute-definitions/" + definition, "name", "rooms"),
                List.of(ofQualification, "personId", other),
                List.of(ofQualification, "personId", other, "userId", doctor),
                List.of(ofQualification, "userId", doctor),
                List.of(ofQualification, "userId", nobody),
                List.of("/qualifications/" + ofPlain, "userId", doctor)));
        String body = read(api, "/hierarchies/" + hierarchy).put("code", "region").toString();
        assertEquals("This write cannot change code. Leave it out, or give the value that a read has.",
                Api.json(api.put("/hierarchies/" + hierarchy, body)).get("message").asText());
        assertEquals("この書き込みでは code を変えられません。指定しないか、読み取りで得た値を指定してください。",
                Api.json(api.withAcceptLanguage("ja").put("/hierarchies/" + hierarchy, body)).get("message")
                        .asText());
        assertEquals(before, RealmExportIT.export(api));

        readAsBodyWorks(api, "/ref-kinds/site", "code");
        readAsBodyWorks(api, "/hierarchies/" + hierarchy, "code");
        readAsBodyWorks(api, "/ref-items/" + item, "kind", "code");
        readAsBodyWorks(api, "/attribute-definitions/" + definition, "target", "name");
        readAsBodyWorks(api, ofQualification, "personId", "userId");
        // Each way to name the stored person works alone.
        ok(api.put(ofQualification, read(api, ofQualification).without("userId").toString()));
        ok(api.put(ofQualification, read(api, ofQualification).without("personId").toString()));
        JsonNode kept = ok(api.get(ofQualification));
        assertEquals(List.of(person, nurse), List.of(kept.get("personId").asText(), kept.get("userId").asText()));
    }

    @Test
    void aPutWithAnotherSourceAndNoExternalIdFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("fixed-source");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String fromHr = ", \"source\": \"hr\", \"externalId\": \"%s\"";
        String unit = created(api.post("/org-units", "{" + NAMES + ", \"validFrom\": \"2020-04-01\""
                + fromHr.formatted("U1") + "}"));
        String person = created(api.post("/people", "{" + NAMES + fromHr.formatted("P1") + "}"));
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"" + fromHr.formatted("Q1") + "}"));
        // A resource that no system made has no source, so any source of a write is another one.
        String plain = created(api.post("/people", "{" + NAMES + "}"));
        // These kinds keep no source at all.
        created(api.post("/ref-kinds", "{\"code\": \"site\", " + NAMES + fromHr.formatted("K1") + "}"));
        String item = created(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", " + NAMES
                + fromHr.formatted("I1") + "}"));
        String hierarchy = created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\""
                + fromHr.formatted("H1") + "}"));
        String definition = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"" + fromHr.formatted("D1") + "}"));
        String order = created(api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\""
                + fromHr.formatted("O1") + "}"));
        List<String> keepSource = List.of("/org-units/" + unit, "/people/" + person, "/qualifications/" + qualification);
        List<String> paths = new ArrayList<>(keepSource);
        paths.addAll(List.of("/people/" + plain, "/ref-kinds/site", "/ref-items/" + item, "/hierarchies/" + hierarchy,
                "/attribute-definitions/" + definition, "/change-orders/" + order));
        ObjectNode before = RealmExportIT.export(api);

        List<List<String>> cases = new ArrayList<>();
        for (String path : paths) {
            cases.add(List.of(path, "source", "payroll"));
        }
        eachFails(api, cases);
        assertEquals(before, RealmExportIT.export(api));

        for (String path : paths) {
            readAsBodyWorks(api, path, "source");
            String suffix = path.substring(path.lastIndexOf('/') + 1);
            ObjectNode body = read(api, path);
            long version = body.get("version").asLong();
            // With an ID, the source names the system of that ID, and the resource keeps its own.
            JsonNode written = ok(api.put(path, body.put("source", "payroll").put("externalId", "X-" + suffix)
                    .toString()));
            assertEquals(keepSource.contains(path) ? "hr" : null, written.path("source").textValue(), path);
            assertTrue(Api.externalIds(written).contains("payroll=X-" + suffix), path);
            assertEquals(version + 1, written.get("version").asLong(), path);
            assertEquals(written.get("externalIds"), read(api, path).get("externalIds"), path);
            assertEquals(List.of("payroll=Y-" + suffix), Api.externalIds(ok(api.put(path, read(api, path)
                    .put("source", "payroll").put("externalId", "Y-" + suffix).toString()))).stream()
                    .filter(id -> id.startsWith("payroll=")).toList(), path);
        }

        // An ID that names another resource of the kind fails before the write writes anything.
        String region = created(api.post("/hierarchies", "{\"code\": \"region\", \"name\": \"R\"}"));
        before = RealmExportIT.export(api);
        assertEquals("409 external_id.taken", outcome(api.put("/hierarchies/" + region, read(api, "/hierarchies/"
                + region).put("name", "Q").put("source", "hr").put("externalId", "H1").toString())));
        assertEquals("400 invalid.required source", outcome(api.put("/hierarchies/" + region, read(api,
                "/hierarchies/" + region).put("name", "Q").put("externalId", "H2").toString()), "field"));
        assertEquals(before, RealmExportIT.export(api));
    }


    // A write of a read with one field set to another value, as JSON.
    private record Case(String path, ObjectNode read, String field, String value) {
    }

    // Resources with a value in each field that only a response fills.
    private record Stored(String parent, String unit, String organization, String nurse, String person, String item,
            String hierarchy, String definition, String qualification, String order, String employment,
            String assignment) {

        String ofUnit() {
            return "/org-units/" + unit;
        }

        String ofItem() {
            return "/ref-items/" + item;
        }

        String ofAssignment() {
            return "/assignments/" + assignment;
        }

        String ofEmployment() {
            return "/employments/" + employment;
        }

        List<String> paths() {
            return List.of(ofUnit(), "/people/" + person, "/ref-kinds/site", ofItem(), "/hierarchies/" + hierarchy,
                    "/attribute-definitions/" + definition, "/qualifications/" + qualification,
                    "/change-orders/" + order);
        }

        ObjectNode link() {
            return JSON.createObjectNode().put("orgUnitId", unit)
                    .put("organizationId", organization);
        }

        ObjectNode time() {
            return JSON.createObjectNode().put("orgUnitId", unit)
                    .put("timeZone", "Asia/Tokyo").put("dayStart", "05:00");
        }
    }

    private static final String OF_EXTERNAL_ID = "/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=U1";

    private static Stored stored(TestRealm realm) throws Exception {
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("WORK_LOCATION", "tokyo", "東京");
        realm.refItem("COST_CENTER", "c1", "経理");
        String fromHr = ", \"source\": \"hr\", \"externalId\": \"%s\"";
        String parent = created(api.post("/org-units", "{" + NAMES + ", \"validFrom\": \"2020-04-01\", "
                + "\"workLocation\": \"tokyo\", \"costCenter\": \"c1\"}"));
        String unit = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\", \"shortName\": \"棟\", \"phoneticName\": \"びょうとう\"}}, \"validFrom\": \"2020-04-01\", "
                + "\"parentId\": \"" + parent + "\"" + fromHr.formatted("U1") + "}"));
        ok(api.putWithVersion("/org-units/" + unit, "{" + NAMES + ", \"validFrom\": \"2025-04-01\"}"));
        String organization = realm.organization("read-only");
        ok(api.putWithVersion("/org-units/" + unit + "/organization", "{\"organizationId\": \"" + organization
                + "\"}"));
        ok(api.putWithVersion("/org-units/" + unit + "/time-settings", "{\"timeZone\": \"Asia/Tokyo\", "
                + "\"dayStart\": \"05:00\"}"));
        String nurse = realm.user("nurse");
        String person = created(api.post("/people", "{\"userId\": \"" + nurse + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"山田\", \"phoneticName\": \"やまだ\"}}" + fromHr.formatted("P1") + "}"));
        created(api.post("/ref-kinds", "{\"code\": \"site\", " + NAMES + fromHr.formatted("K1") + "}"));
        String item = created(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", " + NAMES
                + fromHr.formatted("I1") + "}"));
        for (String from : List.of("2021-01-01", "2090-01-01")) {
            ok(api.putWithVersion("/ref-items/" + item, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                    + "\"" + from + "\"}}, \"validFrom\": \"" + from + "\"}"));
        }
        String hierarchy = created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\""
                + fromHr.formatted("H1") + "}"));
        String definition = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"" + fromHr.formatted("D1") + "}"));
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"" + fromHr.formatted("Q1") + "}"));
        String order = created(api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\""
                + fromHr.formatted("O1") + "}"));
        ok(api.postWithVersion("/change-orders/" + order + "/announce", ""));
        String employment = created(api.post("/employments", "{\"personId\": \"" + person + "\", "
                + "\"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/employments/" + employment + "/terms", "{\"validFrom\": \"2025-04-01\", "
                + "\"workPercent\": 50}"));
        String assignment = created(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", "
                + "\"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2025-04-01\", "
                + "\"displayOrder\": 1}"));
        return new Stored(parent, unit, organization, nurse, person, item, hierarchy, definition, qualification, order,
                employment, assignment);
    }

    // Another value of each field that only a response fills, in a read of its resource.
    private static List<Case> cases(Api api, Stored stored) throws Exception {
        String ofUnit = stored.ofUnit();
        // The details of this read end on 2025-04-01.
        ObjectNode unitRead = read(api, ofUnit + "?asOf=2022-01-01");
        assertEquals(List.of("2025-04-01", "tokyo", stored.organization(), "Asia/Tokyo"), List.of(
                unitRead.get("validTo").asText(), unitRead.get("effectiveWorkLocation").asText(),
                unitRead.get("keycloakOrganizationId").asText(), unitRead.get("timeZone").asText()));
        String other = "\"" + stored.parent() + "\"";
        List<Case> cases = new ArrayList<>();
        for (String field : List.of("id", "name", "shortName", "phoneticName", "validTo", "keycloakOrganizationId",
                "timeZone", "dayStart")) {
            cases.add(new Case(ofUnit, unitRead, field, other));
        }
        for (String field : List.of("legalEntity", "lateral", "rollup")) {
            cases.add(new Case(ofUnit, unitRead, field, "true"));
        }
        String ofPerson = "/people/" + stored.person();
        for (String field : List.of("id", "name", "nameSource", "phoneticName")) {
            cases.add(new Case(ofPerson, read(api, ofPerson), field, "\"KEYCLOAK\""));
        }
        ObjectNode kindRead = read(api, "/ref-kinds/site");
        ObjectNode itemRead = read(api, stored.ofItem());
        assertEquals("2090-01-01", itemRead.get("validTo").asText());
        cases.addAll(List.of(new Case("/ref-kinds/site", kindRead, "id", other),
                new Case("/ref-kinds/site", kindRead, "name", "\"Other\""),
                new Case("/ref-kinds/site", kindRead, "fixed", "true"),
                new Case(stored.ofItem(), itemRead, "id", other),
                new Case(stored.ofItem(), itemRead, "name", "\"Other\""),
                new Case(stored.ofItem(), itemRead, "validTo", "\"2091-01-01\"")));
        String ofOrder = "/change-orders/" + stored.order();
        for (String path : stored.paths().subList(4, 8)) {
            cases.add(new Case(path, read(api, path), "id", other));
        }
        for (String field : List.of("createdAt", "announcedAt")) {
            cases.add(new Case(ofOrder, read(api, ofOrder), field, "\"2020-01-01T00:00:00Z\""));
        }
        cases.add(new Case(ofOrder, read(api, ofOrder), "announcedByUserId", "\"" + stored.nurse() + "\""));
        for (String path : stored.paths()) {
            cases.add(new Case(path, path.equals(ofUnit) ? unitRead : read(api, path), "externalIds",
                    "[{\"source\": \"hr\", \"externalId\": \"X\"}]"));
        }
        ObjectNode details = period(read(api, stored.ofAssignment()), "periods");
        for (String field : List.of("validTo", "effectiveWorkLocation", "effectiveCostCenter")) {
            cases.add(new Case(stored.ofAssignment() + "/details", details, field, "\"2026-04-01\""));
        }
        cases.addAll(List.of(new Case(stored.ofEmployment() + "/terms", period(read(api, stored.ofEmployment()),
                        "terms"), "validTo", "\"2026-04-01\""),
                new Case(ofUnit + "/organization", stored.link(), "orgUnitId", other),
                new Case(ofUnit + "/time-settings", stored.time(), "orgUnitId", other),
                // The write of the ID comes before the check, so this also shows that the failure undoes it.
                new Case("/external-ids", read(api, OF_EXTERNAL_ID).put("externalId", "U9"), "own", "false")));
        return cases;
    }

    @Test
    void aPutWithAnotherValueOfAFieldThatOnlyAResponseFillsFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("read-only");
        Api api = realm.api;
        Stored stored = stored(realm);
        List<Case> cases = cases(api, stored);
        // A read of old names has no validFrom, so a write of it would change the names of today.
        ObjectNode oldNames = read(api, stored.ofItem() + "?asOf=2020-06-01").without("version");
        // No details hold before the start, so the write fails on its own.
        ObjectNode early = period(read(api, stored.ofAssignment()), "periods").without("version");
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        // Each write sends the version of now, so that a case shows its own outcome also when one before it wrote.
        for (Case each : cases) {
            ObjectNode body = each.read().deepCopy().without("version");
            body.set(each.field(), JSON.readTree(each.value()));
            String name = each.path() + " " + each.field() + " " + each.value();
            expected.add(name + " 400 invalid.value_fixed " + each.field());
            got.add(name + " " + outcome(api.putWithVersion(each.path(), body.toString()), "field"));
        }
        expected.addAll(List.of("old names 400 invalid.value_fixed name", "early details 404 assignment.not_valid_on"));
        got.add("old names " + outcome(api.putWithVersion(stored.ofItem(), oldNames.toString()), "field"));
        got.add("early details " + outcome(api.putWithVersion(stored.ofAssignment() + "/details",
                early.put("validFrom", "2019-01-01").toString())));
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        String ofUnit = stored.ofUnit();
        // Each read is the body of a write. A flag that a read leaves out can be false.
        ok(api.put(ofUnit, read(api, ofUnit + "?asOf=2022-01-01").put("legalEntity", false).put("lateral", false)
                .put("rollup", false).toString()));
        // A change inside the details keeps their end.
        ok(api.put(ofUnit, read(api, ofUnit + "?asOf=2022-01-01").put("validFrom", "2023-04-01").toString()));
        List<String> paths = new ArrayList<>(stored.paths());
        paths.add(stored.ofItem() + "?asOf=2095-01-01");
        for (String path : paths) {
            ok(api.put(path.replaceAll("\\?.*", ""), read(api, path).toString()));
        }
        ok(api.put(stored.ofAssignment() + "/details", period(read(api, stored.ofAssignment()), "periods")
                .toString()));
        ok(api.put(stored.ofEmployment() + "/terms", period(read(api, stored.ofEmployment()), "terms").toString()));
        ok(api.putWithVersion(ofUnit + "/organization", stored.link().toString()));
        ok(api.putWithVersion(ofUnit + "/time-settings", stored.time().toString()));
        ok(api.put("/external-ids", read(api, OF_EXTERNAL_ID).toString()));
    }

    // The first period of a read, with the version of the resource, as the body of a change of that period.
    private static ObjectNode period(JsonNode read, String periods) {
        return ((ObjectNode) read.get(periods).get(0).deepCopy()).put("version", read.get("version").asLong());
    }
}

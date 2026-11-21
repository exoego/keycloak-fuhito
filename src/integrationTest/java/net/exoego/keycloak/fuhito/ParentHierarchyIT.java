package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.AdminEventRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.FixedValuesIT.outcome;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A company with a branch, and the rollup unit Kanto that gathers the branch in the hierarchy "region". Each org unit
 * of a response names the hierarchy of its parent.
 */
class ParentHierarchyIT {

    private static final String ON = "asOf=2026-05-01";
    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}";

    private record Realm(TestRealm realm, Api api, String company, String branch, String kanto) {
    }

    private static Realm realm(String prefix) throws Exception {
        TestRealm realm = TestRealm.create(prefix);
        Api api = realm.api;
        created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"region\", " + NAMES
                + ", \"rollup\": true}"));
        created(api.post("/hierarchies", "{\"code\": \"region\", \"name\": \"地域\"}"));
        String company = created(api.post("/org-units", "{" + NAMES + ", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String branch = created(api.post("/org-units", "{" + NAMES + ", \"parentId\": \"" + company
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String kanto = created(api.post("/org-units", "{" + NAMES + ", \"unitType\": \"region\", "
                + "\"validFrom\": \"2026-04-01\"}")).get("id").asText();
        return new Realm(realm, api, company, branch, kanto);
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    // The hierarchy and the parent of each unit of a list.
    private static List<String> parents(Api api, String path) throws Exception {
        return StreamSupport.stream(ok(api.get(path)).spliterator(), false)
                .map(unit -> unit.path("hierarchy").asText() + " " + unit.path("parentId").asText())
                .toList();
    }

    @Test
    void everyOrgUnitOfAResponseNamesTheHierarchyOfItsParent() throws Exception {
        Realm realm = realm("parent-hierarchy-reads");
        Api api = realm.api();
        String branch = "/org-units/" + realm.branch();
        String kanto = "/org-units/" + realm.kanto();
        RealmResource admin = KEYCLOAK.getKeycloakAdminClient().realm(realm.realm().name);
        RealmRepresentation events = admin.toRepresentation();
        events.setAdminEventsEnabled(true);
        events.setAdminEventsDetailsEnabled(true);
        admin.update(events);

        JsonNode moved = ok(api.putWithVersion(branch + "/parent?hierarchy=region", "{\"parentId\": \""
                + realm.kanto() + "\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals("region " + realm.kanto(), moved.get("hierarchy").asText() + " " + moved.get("parentId").asText());
        assertEquals("default", ok(api.putWithVersion(branch, "{" + NAMES + ", \"validFrom\": \"2026-06-01\"}"))
                .get("hierarchy").asText());

        assertEquals("region " + realm.kanto(), parents(api, branch + "/path?hierarchy=region&" + ON).getLast());
        assertEquals("default " + realm.company(), parents(api, branch + "/path?" + ON).getLast());
        assertEquals(List.of("region " + realm.kanto()), parents(api, kanto + "/children?hierarchy=region&" + ON));
        assertEquals(List.of("region ", "region " + realm.kanto()),
                parents(api, kanto + "/subtree?hierarchy=region&" + ON));
        assertEquals(List.of("default ", "default " + realm.company()),
                parents(api, "/org-units/" + realm.company() + "/subtree?hierarchy=default&" + ON));
        assertEquals(List.of("default " + realm.company()),
                parents(api, "/org-units/" + realm.company() + "/children?" + ON));
        assertEquals(Set.of("region ", "region " + realm.kanto()),
                Set.copyOf(parents(api, "/org-units?hierarchy=region&" + ON)));
        assertEquals(Set.of("default ", "default " + realm.company()), Set.copyOf(parents(api, "/org-units?" + ON)));
        assertEquals("region", ok(api.get(branch + "?hierarchy=region&" + ON)).get("hierarchy").asText());
        assertEquals("default", ok(api.get(branch + "?" + ON)).get("hierarchy").asText());
        // A rollup unit has no parent in the default hierarchy, and the read names it all the same.
        JsonNode rollup = ok(api.get(kanto + "?" + ON));
        assertEquals("default", rollup.get("hierarchy").asText());
        assertFalse(rollup.has("parentId"), rollup.toString());
        // A history gives the parents apart from the details.
        for (JsonNode version : ok(api.get(branch + "/history")).get("versions")) {
            assertFalse(version.has("hierarchy"), version.toString());
        }

        // The event of the move has the read of its hierarchy.
        AdminEventRepresentation event = admin.getAdminEvents().stream()
                .filter(e -> ("fuhito/org-units/" + realm.branch() + "/parent").equals(e.getResourcePath()))
                .findFirst()
                .orElseThrow();
        JsonNode recorded = new ObjectMapper().readTree(event.getRepresentation());
        assertEquals("region " + realm.kanto(), recorded.get("hierarchy").asText() + " "
                + recorded.get("parentId").asText());
    }

    @Test
    void aChangeOfDetailsChecksTheParentInTheHierarchyOfItsBody() throws Exception {
        Realm realm = realm("parent-hierarchy-writes");
        Api api = realm.api();
        String branch = "/org-units/" + realm.branch();
        ok(api.putWithVersion(branch + "/parent?hierarchy=region", "{\"parentId\": \"" + realm.kanto()
                + "\", \"validFrom\": \"2026-04-01\"}"));
        ObjectNode inRegion = ok(api.get(branch + "?hierarchy=region&" + ON)).deepCopy();
        ObjectNode inDefault = ok(api.get(branch + "?" + ON)).deepCopy();

        assertEquals("400 invalid.value_fixed parentId", outcome(api.put(branch,
                inRegion.deepCopy().put("parentId", realm.company()).toString()), "field"));
        assertEquals("400 invalid.value_fixed parentId", outcome(api.put(branch,
                inDefault.deepCopy().put("parentId", realm.kanto()).toString()), "field"));
        // A body with no hierarchy is checked in the default one.
        assertEquals("400 invalid.value_fixed parentId", outcome(api.put(branch,
                inRegion.deepCopy().without("hierarchy").toString()), "field"));
        // A hierarchy that does not exist fails as the parameter of a read does, also with nothing else to check.
        assertEquals("400 hierarchy.unknown nope", outcome(api.get(branch + "?hierarchy=nope&" + ON), "code"));
        assertEquals("400 hierarchy.unknown nope", outcome(api.put(branch,
                inRegion.deepCopy().put("hierarchy", "nope").toString()), "code"));
        assertEquals("400 hierarchy.unknown nope", outcome(api.putWithVersion(branch, "{" + NAMES
                + ", \"validFrom\": \"2026-04-01\", \"hierarchy\": \"nope\"}"), "code"));
        assertEquals(inRegion.get("version"), ok(api.get(branch + "?" + ON)).get("version"));
        assertEquals("default", ok(api.put(branch, inDefault.toString())).get("hierarchy").asText());
    }

    @Test
    void aCreateTakesOnlyTheDefaultHierarchy() throws Exception {
        Realm realm = realm("parent-hierarchy-create");
        Api api = realm.api();
        String body = "{" + NAMES + ", \"parentId\": \"" + realm.company() + "\", \"validFrom\": \"2026-04-01\"";

        for (String hierarchy : List.of("region", "nope")) {
            assertEquals("400 invalid.value_fixed hierarchy", outcome(api.post("/org-units", body
                    + ", \"hierarchy\": \"" + hierarchy + "\"}"), "field"), hierarchy);
        }
        for (String unit : List.of(body + "}", body + ", \"hierarchy\": \"default\"}")) {
            JsonNode made = created(api.post("/org-units", unit));
            assertEquals("default " + realm.company(), made.get("hierarchy").asText() + " "
                    + made.get("parentId").asText());
        }
        assertEquals(5, api.count("/org-units/count?" + ON));
    }

    // A row of an org unit gives the parent in the default hierarchy. The list orgUnitParents gives the others.
    @Test
    void aRowOfAnOrgUnitInAnImportTakesOnlyTheDefaultHierarchy() throws Exception {
        Realm realm = realm("parent-hierarchy-import");
        Api api = realm.api();
        String units = """
                {"effectiveDate": "2026-04-01", "orgUnits": [%s]}
                """;
        String row = "{\"id\": \"%s\", " + NAMES + ", \"hierarchy\": \"%s\"%s}";
        String newUnit = "{\"externalId\": \"%s\", \"source\": \"sheet\", " + NAMES + ", \"hierarchy\": \"%s\"}";

        HttpResponse<String> bad = api.post("/imports", units.formatted(String.join(", ",
                newUnit.formatted("N", "region"),
                row.formatted(realm.branch(), "region", ", \"parentId\": \"" + realm.kanto() + "\""),
                row.formatted(realm.company(), "nope", ""))));
        assertEquals(400, bad.statusCode(), bad.body());
        JsonNode errors = Api.json(bad).get("errors");
        assertEquals(List.of("invalid.value_fixed", "invalid.value_fixed", "invalid.value_fixed"),
                errors.findValuesAsText("error"), bad.body());
        assertEquals(List.of("hierarchy", "hierarchy", "hierarchy"), errors.findValuesAsText("field"), bad.body());
        JsonNode report = ok(api.post("/imports", units.formatted(String.join(", ",
                newUnit.formatted("O", "default"),
                row.formatted(realm.branch(), "default", ", \"parentId\": \"" + realm.company() + "\"")))));

        assertEquals(1, report.get("counts").get("orgUnits").get("created").asInt(), report.toString());
        assertEquals(1, report.get("counts").get("orgUnits").get("unchanged").asInt(), report.toString());
        assertEquals(List.of(), parents(api, "/org-units/" + realm.kanto() + "/children?hierarchy=region&" + ON));
        assertEquals("default " + realm.company(), parents(api, "/org-units/" + realm.branch() + "/path?" + ON)
                .getLast());
    }
}

package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A hospital in Tokyo with a ward, a clinic in Osaka, and a team in the ward. A nurse and a remote worker are in the
 * ward, a doctor in the clinic, and a clerk and an intern in the team. The remote worker works from home, and the
 * intern starts in 2027. Nobody writes the units below the hospital or the assignments, but their work location and
 * cost center come from the units above.
 */
class EffectivePlacesIT {

    // The dates on which each unit is read. Today is between the second and the third.
    private static final String[] DATES = {"2020-06-01", "2026-06-01", "2027-06-01"};

    private record Hospital(TestRealm realm, Api api, String hospital, String ward, String clinic, String team,
            String nurse, String remote, String doctor, String clerk, String intern) {
    }

    private static Hospital hospital(String prefix) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        Api api = realm.api;
        for (String site : List.of("tokyo", "osaka", "kyoto", "home")) {
            realm.refItem("WORK_LOCATION", site, site);
        }
        realm.refItem("COST_CENTER", "cc-hq", "本院");
        realm.refItem("COST_CENTER", "cc-new", "新本院");
        String hospital = unit(api, "病院", null, ", \"workLocation\": \"tokyo\", \"costCenter\": \"cc-hq\"");
        String ward = unit(api, "病棟", hospital, "");
        String clinic = unit(api, "診療所", hospital, ", \"workLocation\": \"osaka\"");
        String team = unit(api, "チーム", ward, "");
        return new Hospital(realm, api, hospital, ward, clinic, team, assign(realm, "nurse", ward, "2020-04-01", ""),
                assign(realm, "remote", ward, "2020-04-01", ", \"workLocation\": \"home\""),
                assign(realm, "doctor", clinic, "2020-04-01", ""), assign(realm, "clerk", team, "2020-04-01", ""),
                assign(realm, "intern", team, "2027-04-01", ""));
    }

    private static String unit(Api api, String name, String parentId, String more) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}" + parent + more + ", \"validFrom\": \"2020-04-01\"}"));
    }

    private static String assign(TestRealm realm, String user, String orgUnitId, String from, String more)
            throws Exception {
        String employment = created(realm.api.post("/employments", "{\"userId\": \"" + realm.user(user)
                + "\", \"validFrom\": \"" + from + "\"}"));
        return created(realm.api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + orgUnitId + "\", \"validFrom\": \"" + from + "\", \"primary\": true" + more + "}"));
    }

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String details(String name, String workLocation, String costCenter, String validFrom) {
        return "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}"
                + (workLocation == null ? "" : ", \"workLocation\": \"" + workLocation + "\"")
                + (costCenter == null ? "" : ", \"costCenter\": \"" + costCenter + "\"")
                + ", \"validFrom\": \"" + validFrom + "\"}";
    }

    private static long lastSequence(Api api) throws Exception {
        long last = 0;
        for (JsonNode page = ok(api.get("/change-log?max=1000")); !page.isEmpty();
                page = ok(api.get("/change-log?max=1000&after=" + last))) {
            last = page.get(page.size() - 1).get("sequence").asLong();
        }
        return last;
    }

    // The propagated changes after a number, as the ID of each resource.
    private static Set<String> propagated(Api api, long after) throws Exception {
        Set<String> ids = new TreeSet<>();
        List<String> each = new ArrayList<>();
        for (JsonNode entry : ok(api.get("/change-log?kind=PROPAGATED&max=1000&after=" + after))) {
            ids.add(entry.get("resourceId").asText());
            each.add(entry.get("resourceId").asText());
            assertEquals("WRITE", entry.get("cause").asText(), entry.toString());
        }
        assertEquals(ids.size(), each.size(), "one propagated change for each resource: " + each);
        return ids;
    }

    private static Set<String> set(String... ids) {
        return new TreeSet<>(List.of(ids));
    }

    // The work location that applies to each period of an assignment, or an empty text for none.
    private static List<String> workLocations(Api api, String assignment) throws Exception {
        List<String> places = new ArrayList<>();
        for (JsonNode period : ok(api.get("/assignments/" + assignment)).get("periods")) {
            JsonNode place = period.get("effectiveWorkLocation");
            places.add(place == null || place.isNull() ? "" : place.asText());
        }
        return places;
    }

    @Test
    void aNewWorkLocationOfAUnitChangesTheUnitsBelowAndTheAssignmentsThatTakeIt() throws Exception {
        Hospital h = hospital("places-work-location");
        Api api = h.api();
        String order = created(api.post("/change-orders", "{\"title\": \"京都移転\", "
                + "\"effectiveDate\": \"2026-04-01\"}"));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.hospital() + "?changeOrder=" + order,
                details("病院", "kyoto", "cc-hq", "2026-04-01")));

        reads.assertEveryChangeIsLogged();
        // The clinic has its own work location, and so do the members of the clinic and the remote worker.
        assertEquals(set(h.ward(), h.team(), h.nurse(), h.clerk(), h.intern()), propagated(api, last));
        assertEquals(List.of("kyoto"), workLocations(api, h.nurse()));
        assertEquals(List.of("home"), workLocations(api, h.remote()));
        assertEquals("tokyo", ok(api.get("/org-units/" + h.team() + "?asOf=2026-03-31"))
                .get("effectiveWorkLocation").asText());
        // Each propagated change names the entry of the write and its change order, and has no user.
        JsonNode log = ok(api.get("/change-log?after=" + last));
        JsonNode write = log.get(0);
        assertEquals(List.of("WRITE", h.hospital(), order), List.of(write.get("kind").asText(),
                write.get("resourceId").asText(), write.get("changeOrderId").asText()));
        for (JsonNode entry : log.valueStream().skip(1).toList()) {
            assertEquals(List.of("PROPAGATED", write.get("sequence").asText(), order),
                    List.of(entry.get("kind").asText(), entry.get("causeSequence").asText(),
                            entry.get("changeOrderId").asText()));
            assertFalse(entry.has("actorUserId"), entry.toString());
        }
        assertEquals(6, api.count("/change-orders/" + order + "/changes/count"));
    }

    @Test
    void aChangeFromALaterDateChangesTheReadsOfThatDateOnly() throws Exception {
        Hospital h = hospital("places-later");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.hospital(), details("病院", "kyoto", "cc-hq", "2027-01-01")));

        reads.assertEveryChangeIsLogged();
        // An assignment reads each period on its date nearest to today. Only the period of the intern starts later.
        assertEquals(set(h.ward(), h.team(), h.intern()), propagated(api, last));
        assertEquals(List.of("tokyo"), workLocations(api, h.clerk()));
        assertEquals(List.of("kyoto"), workLocations(api, h.intern()));
    }

    @Test
    void aNewCostCenterChangesEveryUnitAndAssignmentWithNoCostCenterOfItsOwn() throws Exception {
        Hospital h = hospital("places-cost-center");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.hospital(), details("病院", "tokyo", "cc-new", "2026-04-01")));

        reads.assertEveryChangeIsLogged();
        assertEquals(set(h.ward(), h.clinic(), h.team(), h.nurse(), h.remote(), h.doctor(), h.clerk(), h.intern()),
                propagated(api, last));
    }

    @Test
    void aNewWorkLocationOfTheOrgUnitOfAnAssignmentChangesItsRead() throws Exception {
        Hospital h = hospital("places-own-unit");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.ward(), details("病棟", "kyoto", null, "2020-04-01")));

        reads.assertEveryChangeIsLogged();
        assertEquals(set(h.team(), h.nurse(), h.clerk(), h.intern()), propagated(api, last));
    }

    @Test
    void aMoveInTheDefaultHierarchyChangesTheReadsBelowTheMovedUnit() throws Exception {
        Hospital h = hospital("places-move");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.team() + "/parent", "{\"parentId\": \"" + h.clinic()
                + "\", \"validFrom\": \"2026-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        assertEquals(set(h.clerk(), h.intern()), propagated(api, last));
        assertEquals(List.of("osaka"), workLocations(api, h.clerk()));
    }

    @Test
    void aUnitThatLeavesTheDefaultHierarchyTakesNothingFromAboveAndAMoveInAnotherOneChangesNoRead()
            throws Exception {
        Hospital h = hospital("places-leave");
        Api api = h.api();
        created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"Project\"}"));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.ward() + "/parent?hierarchy=project", "{\"parentId\": \""
                + h.clinic() + "\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals(set(), propagated(api, last));

        ok(api.putWithVersion("/org-units/" + h.ward() + "/parent", "{\"validFrom\": \"2026-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        // The remote worker has a work location of its own, and loses the cost center.
        assertEquals(set(h.team(), h.nurse(), h.remote(), h.clerk(), h.intern()), propagated(api, last));
        assertEquals(List.of(""), workLocations(api, h.nurse()));
        // The unit joins it again.
        long left = lastSequence(api);
        ok(api.putWithVersion("/org-units/" + h.ward() + "/parent", "{\"parentId\": \"" + h.hospital()
                + "\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals(set(h.team(), h.nurse(), h.remote(), h.clerk(), h.intern()), propagated(api, left));
        assertEquals(List.of("tokyo"), workLocations(api, h.nurse()));
    }

    @Test
    void aReorganizationGivesEachReadThatItsStepsChangeOnePropagatedChange() throws Exception {
        Hospital h = hospital("places-reorganization");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.postWithVersion("/reorganizations", """
                {"effectiveDate": "2026-04-01", "steps": [
                  {"op": "change", "id": "%s", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}},
                   "workLocation": "kyoto", "costCenter": "cc-hq"},
                  {"op": "move", "id": "%s", "parentId": "%s"}
                ]}""".formatted(h.hospital(), h.team(), h.clinic())));

        reads.assertEveryChangeIsLogged();
        // The team has the entry of its move, so it gets no propagated change.
        assertEquals(set(h.ward(), h.nurse(), h.clerk(), h.intern()), propagated(api, last));
        assertEquals(List.of("osaka"), workLocations(api, h.clerk()));
    }

    @Test
    void anImportAndACsvImportOfUnitsAndParentsGiveThePropagatedChanges() throws Exception {
        Hospital h = hospital("places-import");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [{"id": "%s", "workLocation": "kyoto"}],
                 "orgUnitParents": [{"hierarchy": "default", "orgUnitId": "%s", "parentId": "%s"}]}"""
                .formatted(h.hospital(), h.team(), h.clinic())));

        reads.assertEveryChangeIsLogged();
        assertEquals(set(h.ward(), h.nurse(), h.clerk(), h.intern()), propagated(api, last));

        long csv = lastSequence(api);
        DerivedReads again = DerivedReads.before(api, DATES);
        ok(api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01", "text/csv; charset=UTF-8",
                ("id,workLocation\r\n" + h.clinic() + ",home\r\n").getBytes(StandardCharsets.UTF_8)));
        ok(api.postBytes("/imports/csv/org-unit-parents?effectiveDate=2026-05-01", "text/csv; charset=UTF-8",
                ("hierarchy,orgUnitId,parentId\r\ndefault," + h.ward() + "," + h.clinic() + "\r\n")
                        .getBytes(StandardCharsets.UTF_8)));

        again.assertEveryChangeIsLogged();
        assertEquals(set(h.team(), h.doctor(), h.nurse(), h.clerk(), h.intern()), propagated(api, csv));
        assertEquals(List.of("home"), workLocations(api, h.nurse()));
    }

    @Test
    void aRenameANewUnitAndTheEndOfAUnitChangeNoOtherRead() throws Exception {
        Hospital h = hospital("places-no-change");
        Api api = h.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api, DATES);

        ok(api.putWithVersion("/org-units/" + h.hospital(), details("総合病院", "tokyo", "cc-hq", "2026-04-01")));
        String annex = unit(api, "別館", h.ward(), ", \"workLocation\": \"kyoto\"");
        ok(api.putWithVersion("/org-units/" + annex + "/end", "{\"validTo\": \"2026-04-01\"}"));
        ok(api.deleteWithVersion("/org-units/" + annex + "/end"));
        ok(api.putWithVersion("/org-units/" + annex, details("別館", null, null, "2020-04-01")));

        reads.assertEveryChangeIsLogged();
        assertEquals(set(), propagated(api, last));
    }

    @Test
    void aClientThatHoldsAnOldReadOfAnAssignmentGets409AfterANewWorkLocationOfAUnitAbove() throws Exception {
        Hospital h = hospital("places-conflict");
        Api api = h.api();
        long read = api.version("/assignments/" + h.clerk());

        ok(api.putWithVersion("/org-units/" + h.hospital(), details("病院", "kyoto", "cc-hq", "2026-04-01")));

        HttpResponse<String> stale = api.put("/assignments/" + h.clerk() + "/details", "{\"version\": " + read
                + ", \"validFrom\": \"2026-10-01\", \"head\": true}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("version.conflict", Api.json(stale).get("error").asText());
        assertEquals(read + 1, api.version("/assignments/" + h.clerk()));
    }
}

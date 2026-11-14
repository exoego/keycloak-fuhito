package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUnitServiceTest {

    // 2026-06-15 in Tokyo. It is still 2026-06-14 in UTC.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-14T16:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignments = new MemoryAssignmentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final OrgUnitService service = new OrgUnitService(store, assignments, refItems, CLOCK);

    private static NewOrgUnit named(String name, String parentId, String validFrom) {
        return new NewOrgUnit(null, name, null, null, null, null, null, parentId, validFrom, null, null, null, null);
    }

    private FuhitoException failure(NewOrgUnit request) {
        return assertThrows(FuhitoException.class, () -> service.create(request));
    }

    private RefItem unitType(String code, boolean active) {
        RefItem item = new RefItem("type-" + code, RefKind.UNIT_TYPE, code, code, Map.of(), 0, Map.of(), active);
        refItems.insert(item);
        return item;
    }

    @Test
    void createsARootWithOnlyANameAndADate() {
        OrgUnitState state = service.create(named("Clinic", null, "2026-04-01"));

        assertEquals(36, state.unit().id().length());
        assertNull(state.unit().code());
        assertNull(state.unit().keycloakOrganizationId());
        assertNull(state.parentId());
        assertNull(state.unitType());
        OrgUnitVersion version = state.version();
        assertEquals(state.unit().id(), version.orgUnitId());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), version.period());
        assertEquals("Clinic", version.name());
        assertEquals(Map.of(), version.names());
        assertNull(version.shortName());
        assertEquals(Map.of(), version.shortNames());
        assertNull(version.phoneticName());
        assertNull(version.unitTypeId());
        assertEquals(0, version.displayOrder());
        assertEquals(Map.of(), version.attributes());
        assertTrue(store.links.isEmpty());
    }

    @Test
    void createsAChildWithEveryValue() {
        RefItem department = unitType("department", true);
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();

        OrgUnitState state = service.create(new NewOrgUnit("naika", " 内科 ", Map.of("en", "Internal Medicine"), "内",
                Map.of("en", "IM"), "ないか", "department", parentId, "2026-04-01", 3, Map.of("floor", 2), "D-001",
                "hr-system"));

        assertEquals("naika", state.unit().code());
        assertEquals("D-001", state.unit().externalId());
        assertEquals("hr-system", state.unit().source());
        assertEquals("department", state.unitType());
        assertEquals(parentId, state.parentId());
        OrgUnitVersion version = state.version();
        assertEquals("内科", version.name());
        assertEquals(Map.of("en", "Internal Medicine"), version.names());
        assertEquals("内", version.shortName());
        assertEquals(Map.of("en", "IM"), version.shortNames());
        assertEquals("ないか", version.phoneticName());
        assertEquals(department.id(), version.unitTypeId());
        assertEquals(3, version.displayOrder());
        assertEquals(Map.of("floor", 2), version.attributes());

        OrgUnitLink link = store.links.getFirst();
        assertEquals(36, link.id().length());
        assertEquals("default-hierarchy", link.hierarchyId());
        assertEquals(state.unit().id(), link.childId());
        assertEquals(parentId, link.parentId());
        assertEquals(version.period(), link.period());
    }

    @Test
    void aChildMayStartAfterItsParent() {
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();

        assertEquals(parentId, service.create(named("Ward", parentId, "2027-01-01")).parentId());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.create(null)).code());
        assertEquals("invalid.required", failure(named("x", null, null)).code());
        assertEquals("invalid.date", failure(named("x", null, "April 1")).code());
        assertEquals("invalid.name", failure(named(" ", null, "2026-04-01")).code());
        assertEquals("invalid.code", failure(new NewOrgUnit("bad code", "x", null, null, null, null, null, null,
                "2026-04-01", null, null, null, null)).code());
        assertTrue(store.units.isEmpty());
    }

    @Test
    void rejectsACodeThatIsTaken() {
        NewOrgUnit request = new NewOrgUnit("hq", "x", null, null, null, null, null, null, "2026-04-01", null, null,
                null, null);
        service.create(request);

        FuhitoException e = failure(request);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("org_unit.code_taken", e.code());
        assertEquals(Map.of("code", "hq"), e.params());
    }

    @Test
    void rejectsAnUnknownOrInactiveUnitType() {
        unitType("retired", false);

        for (String unitType : new String[] {"nope", "retired"}) {
            FuhitoException e = failure(new NewOrgUnit(null, "x", null, null, null, null, unitType, null,
                    "2026-04-01", null, null, null, null));
            assertEquals(FuhitoException.Reason.INVALID, e.reason());
            assertEquals("org_unit.unit_type_unknown", e.code());
            assertEquals(Map.of("unitType", unitType), e.params());
        }
    }

    @Test
    void rejectsAnUnknownParent() {
        FuhitoException e = failure(named("x", "no-such-parent", "2026-04-01"));

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("org_unit.parent_unknown", e.code());
    }

    @Test
    void rejectsAParentThatStartsLater() {
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();

        FuhitoException e = failure(named("Ward", parentId, "2026-03-31"));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("org_unit.parent_not_valid", e.code());
        assertEquals(Map.of("parentId", parentId, "validFrom", "2026-03-31"), e.params());
    }

    @Test
    void rejectsAParentThatEnds() {
        store.versions.add(new OrgUnitVersion("v1", "closing", new ValidPeriod(LocalDate.of(2026, 4, 1),
                LocalDate.of(2027, 4, 1)), "Closing", Map.of(), null, Map.of(), null, null, 0, Map.of()));

        assertEquals("org_unit.parent_not_valid", failure(named("Ward", "closing", "2026-04-01")).code());
    }

    private static OrgUnitChange rename(String validFrom, String name) {
        return new OrgUnitChange(validFrom, name, null, null, null, null, null, null, null);
    }

    @Test
    void aChangeSplitsTheDetailsAtItsDate() {
        String id = service.create(named("Old", null, "2026-04-01")).unit().id();

        OrgUnitState changed = service.changeDetails(id, rename("2026-10-01", "New"));

        assertEquals(id, changed.unit().id());
        assertEquals("New", changed.version().name());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 10, 1)), changed.version().period());
        assertEquals("Old", service.get(id, "2026-09-30").version().name());
        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1)),
                service.get(id, "2026-09-30").version().period());
        assertEquals("New", service.get(id, "2026-10-01").version().name());
    }

    @Test
    void aChangeInsideOlderDetailsKeepsTheLaterOnes() {
        String id = service.create(named("A", null, "2026-04-01")).unit().id();
        service.changeDetails(id, rename("2027-04-01", "C"));

        OrgUnitState changed = service.changeDetails(id, rename("2026-10-01", "B"));

        assertEquals(new ValidPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)), changed.version().period());
        assertEquals("A", service.get(id, "2026-09-30").version().name());
        assertEquals("B", service.get(id, "2027-03-31").version().name());
        assertEquals("C", service.get(id, "2027-04-01").version().name());
        assertEquals(3, store.versions(id).size());
    }

    @Test
    void aChangeOnTheFirstDateOfDetailsCorrectsThem() {
        String id = service.create(named("Typo", null, "2026-04-01")).unit().id();
        String versionId = store.versions(id).getFirst().id();

        OrgUnitState changed = service.changeDetails(id, rename("2026-04-01", "Fixed"));

        assertEquals(versionId, changed.version().id());
        assertEquals("Fixed", changed.version().name());
        assertEquals(1, store.versions(id).size());
    }

    @Test
    void aChangeReplacesAllDetails() {
        unitType("ward", true);
        RefItem clinic = unitType("clinic", true);
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();
        String id = service.create(new NewOrgUnit("w1", "Ward", Map.of("ja", "病棟"), "W", Map.of("ja", "病"), "わーど",
                "ward", parentId, "2026-04-01", 9, Map.of("beds", 30), "X-1", "hr")).unit().id();

        OrgUnitState changed = service.changeDetails(id, new OrgUnitChange("2026-10-01", "Clinic",
                Map.of("ja", "診療所"), "C", Map.of("ja", "診"), "くりにっく", "clinic", 2, Map.of("rooms", 3)));

        OrgUnitVersion v = changed.version();
        assertEquals("Clinic", v.name());
        assertEquals(Map.of("ja", "診療所"), v.names());
        assertEquals("C", v.shortName());
        assertEquals(Map.of("ja", "診"), v.shortNames());
        assertEquals("くりにっく", v.phoneticName());
        assertEquals(clinic.id(), v.unitTypeId());
        assertEquals("clinic", changed.unitType());
        assertEquals(2, v.displayOrder());
        assertEquals(Map.of("rooms", 3), v.attributes());
        // Not details, so they stay.
        assertEquals("w1", changed.unit().code());
        assertEquals("X-1", changed.unit().externalId());
        assertEquals(parentId, changed.parentId());

        OrgUnitState cleared = service.changeDetails(id, rename("2027-04-01", "Bare"));
        assertNull(cleared.version().shortName());
        assertNull(cleared.version().phoneticName());
        assertNull(cleared.unitType());
        assertEquals(Map.of(), cleared.version().attributes());
        assertEquals(0, cleared.version().displayOrder());
    }

    @Test
    void aChangeMayKeepADeactivatedUnitTypeButNotTakeANewOne() {
        RefItem ward = unitType("ward", true);
        unitType("retired", false);
        String id = service.create(new NewOrgUnit(null, "Ward", null, null, null, null, "ward", null, "2026-04-01",
                null, null, null, null)).unit().id();
        refItems.update(new RefItem(ward.id(), RefKind.UNIT_TYPE, "ward", "ward", Map.of(), 0, Map.of(), false));

        assertEquals("ward", service.changeDetails(id, new OrgUnitChange("2026-10-01", "Ward 2", null, null, null,
                null, "ward", null, null)).unitType());
        for (String unitType : new String[] {"retired", "nope"}) {
            assertEquals("org_unit.unit_type_unknown", assertThrows(FuhitoException.class,
                    () -> service.changeDetails(id, new OrgUnitChange("2027-04-01", "x", null, null, null, null,
                            unitType, null, null))).code());
        }
    }

    @Test
    void rejectsABadChange() {
        String id = service.create(named("A", null, "2026-04-01")).unit().id();

        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.changeDetails(id, null)).code());
        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.changeDetails(id, rename(null, "x"))).code());
        assertEquals("invalid.name",
                assertThrows(FuhitoException.class, () -> service.changeDetails(id, rename("2026-10-01", " "))).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.changeDetails(id, rename("2026-03-31", "x"))).code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.changeDetails("nope", rename("2026-10-01", "x"))).code());
        assertEquals(1, store.versions(id).size());
        assertEquals("A", service.get(id, "2026-10-01").version().name());
    }

    private String root(String name) {
        return service.create(named(name, null, "2026-04-01")).unit().id();
    }

    private String child(String name, String parentId) {
        return service.create(named(name, parentId, "2026-04-01")).unit().id();
    }

    private FuhitoException moveFailure(String id, String parentId, String validFrom) {
        return assertThrows(FuhitoException.class, () -> service.move(id, parentId, validFrom));
    }

    @Test
    void movesAUnitFromADate() {
        String a = root("A");
        String b = root("B");
        String unit = child("Unit", a);

        OrgUnitState moved = service.move(unit, b, "2026-10-01");

        assertEquals(b, moved.parentId());
        assertEquals(a, service.get(unit, "2026-09-30").parentId());
        assertEquals(b, service.get(unit, "2030-01-01").parentId());
        assertEquals(2, store.links.size());
    }

    @Test
    void makesAUnitARootAndPutsARootUnderAParent() {
        String a = root("A");
        String unit = child("Unit", a);

        assertNull(service.move(unit, null, "2026-10-01").parentId());
        assertEquals(a, service.get(unit, "2026-09-30").parentId());
        assertEquals(1, store.links.size());

        assertNull(service.move(unit, null, "2026-11-01").parentId());
        assertEquals(1, store.links.size());

        assertEquals(a, service.move(unit, a, "2027-04-01").parentId());
        assertNull(service.get(unit, "2027-03-31").parentId());
    }

    @Test
    void anEarlierMoveKeepsALaterOne() {
        String a = root("A");
        String b = root("B");
        String c = root("C");
        String unit = child("Unit", a);
        service.move(unit, c, "2027-04-01");

        service.move(unit, b, "2026-10-01");

        assertEquals(a, service.get(unit, "2026-09-30").parentId());
        assertEquals(b, service.get(unit, "2027-03-31").parentId());
        assertEquals(c, service.get(unit, "2027-04-01").parentId());
    }

    @Test
    void aMoveOfARootLastsUntilTheNextStoredMove() {
        String a = root("A");
        String b = root("B");
        String unit = root("Unit");
        service.move(unit, b, "2027-04-01");

        service.move(unit, a, "2026-10-01");

        assertNull(service.get(unit, "2026-09-30").parentId());
        assertEquals(a, service.get(unit, "2027-03-31").parentId());
        assertEquals(b, service.get(unit, "2027-04-01").parentId());
    }

    @Test
    void aMoveOnTheFirstDateOfALinkCorrectsIt() {
        String a = root("A");
        String b = root("B");
        String unit = child("Unit", a);

        assertEquals(b, service.move(unit, b, "2026-04-01").parentId());
        assertEquals(1, store.links.size());

        assertNull(service.move(unit, null, "2026-04-01").parentId());
        assertTrue(store.links.isEmpty());
    }

    @Test
    void rejectsAMoveBelowItself() {
        String a = root("A");
        String b = child("B", a);
        String c = child("C", b);

        for (String target : new String[] {a, b, c}) {
            FuhitoException e = moveFailure(a, target, "2026-10-01");
            assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
            assertEquals("org_unit.cycle", e.code());
            assertEquals(Map.of("id", a), e.params());
        }
        assertNull(service.get(a, "2026-10-01").parentId());
    }

    @Test
    void aCycleCountsOnlyOnTheDatesOfTheMove() {
        String a = root("A");
        String b = child("B", a);
        service.move(b, null, "2027-04-01");

        // B is below A until 2027-04-01, so A can go below B only from that date.
        assertEquals("org_unit.cycle", moveFailure(a, b, "2026-10-01").code());
        assertEquals(b, service.move(a, b, "2027-04-01").parentId());
        // And now B cannot go back below A.
        assertEquals("org_unit.cycle", moveFailure(b, a, "2028-04-01").code());
    }

    @Test
    void rejectsABadMove() {
        String a = root("A");
        String unit = child("Unit", a);
        String later = service.create(named("Later", null, "2027-04-01")).unit().id();

        assertEquals("invalid.required", moveFailure(unit, a, null).code());
        assertEquals("org_unit.not_found", moveFailure("nope", a, "2026-10-01").code());
        assertEquals("org_unit.not_valid_on", moveFailure(unit, a, "2026-03-31").code());
        assertEquals("org_unit.parent_unknown", moveFailure(unit, "nope", "2026-10-01").code());
        assertEquals("org_unit.parent_not_valid", moveFailure(unit, later, "2026-10-01").code());
        assertEquals(a, service.get(unit, "2030-01-01").parentId());
        assertEquals(1, store.links.size());
    }

    @Test
    void aNewParentLastsAsLongAsTheUnit() {
        String a = root("A");
        store.insertUnit(new OrgUnit("ending", null, null, null, null));
        store.versions.add(new OrgUnitVersion("v1", "ending", new ValidPeriod(LocalDate.of(2026, 4, 1),
                LocalDate.of(2027, 4, 1)), "Ending", Map.of(), null, Map.of(), null, null, 0, Map.of()));

        service.move("ending", a, "2026-10-01");

        assertEquals(new ValidPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)), store.links.getFirst().period());
    }

    private FuhitoException endFailure(String id, String validTo) {
        return assertThrows(FuhitoException.class, () -> service.end(id, validTo));
    }

    @Test
    void endsAUnitAndItsParentLink() {
        String a = root("A");
        String unit = child("Unit", a);

        OrgUnitState last = service.end(unit, "2027-04-01");

        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), last.version().period());
        assertEquals(a, last.parentId());
        assertEquals(last, service.get(unit, "2027-03-31"));
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.get(unit, "2027-04-01")).code());
        assertEquals(LocalDate.of(2027, 4, 1), store.links.getFirst().period().to());
    }

    @Test
    void endsARootAndKeepsEarlierDetails() {
        String unit = root("Old name");
        service.changeDetails(unit, rename("2026-10-01", "New name"));

        service.end(unit, "2027-04-01");

        assertEquals("Old name", service.get(unit, "2026-09-30").version().name());
        assertEquals("New name", service.get(unit, "2027-03-31").version().name());
    }

    @Test
    void keepsAParentLinkThatEndedEarlier() {
        String a = root("A");
        String unit = child("Unit", a);
        service.move(unit, null, "2026-10-01");

        service.end(unit, "2027-04-01");

        assertEquals(LocalDate.of(2026, 10, 1), store.links.getFirst().period().to());
    }

    @Test
    void doesNotEndAUnitWithChildren() {
        String a = root("A");
        child("B", a);
        String moving = child("C", a);
        service.move(moving, null, "2027-04-01");

        FuhitoException e = endFailure(a, "2027-01-01");
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("org_unit.has_children", e.code());
        assertEquals(Map.of("id", a, "validTo", "2027-01-01", "count", "2"), e.params());
        // C leaves on 2027-04-01, so only B is in the way on that date.
        assertEquals("1", endFailure(a, "2027-04-01").params().get("count"));
    }

    @Test
    void endsAParentOnTheDateItsLastChildLeaves() {
        String a = root("A");
        String b = child("B", a);
        service.end(b, "2027-04-01");

        assertEquals("org_unit.has_children", endFailure(a, "2027-03-31").code());
        assertEquals(LocalDate.of(2027, 4, 1), service.end(a, "2027-04-01").version().period().to());
    }

    @Test
    void doesNotEndAUnitTwiceOrBeforeLaterChanges() {
        String a = root("A");
        String unit = child("Unit", a);
        service.changeDetails(unit, rename("2027-04-01", "Renamed"));
        service.move(unit, null, "2028-04-01");

        assertEquals("org_unit.later_changes_exist", endFailure(unit, "2027-01-01").code());
        assertEquals("org_unit.later_changes_exist", endFailure(unit, "2027-04-01").code());
        assertEquals("org_unit.later_changes_exist", endFailure(unit, "2028-01-01").code());
        service.move(unit, a, "2028-04-01");
        assertEquals("org_unit.later_changes_exist", endFailure(unit, "2028-04-01").code());

        service.end(unit, "2029-04-01");
        FuhitoException e = endFailure(unit, "2030-04-01");
        assertEquals("org_unit.already_ended", e.code());
        assertEquals(Map.of("id", unit, "validTo", "2029-04-01"), e.params());
    }

    private void assign(String assignmentId, String orgUnitId, String from, String to) {
        assignments.insert(new Assignment(assignmentId + "-" + from, assignmentId, "employment", orgUnitId,
                new ValidPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), null, null, null,
                false, false, null, 0, Map.of(), null, null));
    }

    @Test
    void doesNotEndAUnitWithAssignmentsOnOrAfterTheDate() {
        String unit = root("Unit");
        String other = root("Other");
        assign("open", unit, "2026-04-01", null);
        assign("changed", unit, "2026-04-01", "2026-10-01");
        assign("changed", unit, "2026-10-01", "2027-10-01");
        assign("ended", unit, "2026-04-01", "2027-04-01");
        assign("elsewhere", other, "2026-04-01", null);

        FuhitoException e = endFailure(unit, "2027-04-01");

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("org_unit.has_assignments", e.code());
        assertEquals(Map.of("id", unit, "validTo", "2027-04-01", "count", "2"), e.params());
        assertEquals("3", endFailure(unit, "2027-01-01").params().get("count"));

        assignments.rows.removeIf(row -> row.assignmentId().equals("open"));
        assertEquals("1", endFailure(unit, "2027-04-01").params().get("count"));
        assertEquals(LocalDate.of(2027, 10, 1), service.end(unit, "2027-10-01").version().period().to());
    }

    @Test
    void rejectsABadEnd() {
        String unit = root("Unit");

        assertEquals("invalid.required", endFailure(unit, null).code());
        assertEquals("org_unit.not_found", endFailure("nope", "2027-04-01").code());
        assertEquals("org_unit.not_valid_on", endFailure(unit, "2026-04-01").code());
        assertEquals("org_unit.not_valid_on", endFailure(unit, "2026-01-01").code());
        assertEquals(LocalDate.of(2026, 4, 2), service.end(unit, "2026-04-02").version().period().to());
    }

    @Test
    void anEndedUnitCannotBeAParentAfterItsEnd() {
        String a = root("A");
        String unit = root("Unit");
        service.end(a, "2027-04-01");

        assertEquals("org_unit.parent_not_valid", failure(named("Child", a, "2026-10-01")).code());
        assertEquals("org_unit.parent_not_valid", moveFailure(unit, a, "2026-10-01").code());
    }

    private String unit(String name, String parentId, Integer displayOrder, String phoneticName) {
        return service.create(new NewOrgUnit(null, name, null, null, null, phoneticName, null, parentId,
                "2026-04-01", displayOrder, null, null, null)).unit().id();
    }

    private static List<String> names(List<OrgUnitState> states) {
        return states.stream().map(s -> s.version().name()).toList();
    }

    @Test
    void listsTheTreeWithParentsBeforeChildren() {
        String x = unit("X Hospital", null, 1, null);
        String y = unit("Y Hospital", null, 2, null);
        String nursing = unit("Nursing", x, 2, null);
        unit("Medicine", x, 1, null);
        unit("Ward 2", nursing, 0, null);
        unit("Ward 1", nursing, 0, null);
        unit("Admin", y, 0, null);

        assertEquals(List.of("X Hospital", "Medicine", "Nursing", "Ward 1", "Ward 2", "Y Hospital", "Admin"),
                names(service.list("2026-04-01", false, null, Page.of(null, null))));
        assertEquals(List.of("X Hospital", "Y Hospital"), names(service.list("2026-04-01", true, null, Page.of(null, null))));
        assertEquals(List.of("Medicine", "Nursing"), names(service.children(x, "2026-04-01", null, Page.of(null, null))));
        assertEquals(List.of("Admin"), names(service.children(y, "2026-04-01", null, Page.of(null, null))));
        assertEquals(List.of("Nursing", "Ward 1", "Ward 2"), names(service.subtree(nursing, "2026-04-01", null, Page.of(null, null))));
        assertEquals(nursing, service.subtree(nursing, "2026-04-01", null, Page.of(null, null)).get(1).parentId());
        assertEquals(List.of("Nursing", "Ward 1"), names(service.list("2026-04-01", false, null, Page.of(2, 2))));
        assertEquals(List.of("Y Hospital"), names(service.list("2026-04-01", true, null, Page.of(1, 5))));
        assertEquals(List.of("Nursing"), names(service.children(x, "2026-04-01", null, Page.of(1, 1))));
        assertEquals(List.of("Ward 2"), names(service.subtree(nursing, "2026-04-01", null, Page.of(2, 1))));
    }

    @Test
    void siblingsFollowDisplayOrderThenPhoneticNameThenName() {
        String root = root("Root");
        unit("外科", root, 1, "げか");
        unit("内科", root, 1, "ないか");
        unit("眼科", root, 1, "がんか");
        unit("Zebra", root, 1, null);
        unit("Alpha", root, 1, null);
        unit("総務", root, 0, "そうむ");

        assertEquals(List.of("総務", "眼科", "外科", "内科", "Alpha", "Zebra"),
                names(service.children(root, "2026-04-01", null, Page.of(null, null))));
    }

    @Test
    void readsThePathFromTheRoot() {
        String a = root("A");
        String b = child("B", a);
        String c = child("C", b);

        assertEquals(List.of("A", "B", "C"), names(service.path(c, "2026-04-01")));
        assertEquals(List.of("A"), names(service.path(a, "2026-04-01")));
    }

    @Test
    void theTreeDependsOnTheDate() {
        String a = root("A");
        String b = root("B");
        String moving = child("Moving", a);
        String ending = child("Ending", a);
        service.move(moving, b, "2027-04-01");
        service.changeDetails(moving, rename("2027-04-01", "Moved"));
        service.end(ending, "2027-04-01");
        service.create(named("New", b, "2027-04-01"));

        assertEquals(List.of("A", "Ending", "Moving", "B"), names(service.list("2027-03-31", false, null, Page.of(null, null))));
        assertEquals(List.of("A", "B", "Moved", "New"), names(service.list("2027-04-01", false, null, Page.of(null, null))));
        assertEquals(List.of(), names(service.list("2026-03-31", false, null, Page.of(null, null))));
        assertEquals(List.of("B", "Moved"), names(service.path(moving, "2027-04-01")));
        assertEquals(4, service.list(null, false, null, Page.of(null, null)).size());
    }

    @Test
    void treeReadsRejectAUnitThatIsNotThere() {
        String a = root("A");

        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.children("nope", "2026-04-01", null, Page.of(null, null))).code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.subtree("nope", "2026-04-01", null, Page.of(null, null))).code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.path("nope", "2026-04-01")).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.children(a, "2026-03-31", null, Page.of(null, null))).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.subtree(a, "2026-03-31", null, Page.of(null, null))).code());
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.path(a, "2026-03-31"));
        assertEquals("org_unit.not_valid_on", e.code());
        assertEquals(Map.of("id", a, "date", "2026-03-31"), e.params());
        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> service.list("x", false, null, Page.of(null, null))).code());
    }

    @Test
    void aSecondHierarchyHasItsOwnParentsCyclesAndEnds() {
        store.hierarchyCodes.put("matrix-id", "matrix");
        String a = root("A");
        String b = root("B");
        String ward = service.create(named("Ward", a, "2026-04-01")).unit().id();

        // B is the parent of the ward in the matrix, and A stays its parent in the default hierarchy.
        assertEquals(b, service.move(ward, b, "2026-04-01", "matrix").parentId());
        assertEquals(a, service.get(ward, "2026-04-01").parentId());
        assertEquals(List.of("default-hierarchy", "matrix-id"),
                store.links.stream().map(OrgUnitLink::hierarchyId).toList());

        // A cycle is judged inside one hierarchy. B under the ward is a cycle only in the matrix.
        assertEquals("org_unit.cycle", assertThrows(FuhitoException.class,
                () -> service.move(b, ward, "2026-04-01", "matrix")).code());
        assertEquals(ward, service.move(b, ward, "2026-04-01").parentId());
        service.move(b, null, "2026-04-01");

        OrgUnitHistory history = service.history(ward);
        assertEquals(List.of("default " + a, "matrix " + b), history.parents().stream()
                .map(link -> history.hierarchyCodes().get(link.hierarchyId()) + " " + link.parentId()).toList());

        // A child in any hierarchy keeps its parent from ending. The end of a unit ends its links everywhere.
        assertEquals("org_unit.has_children", assertThrows(FuhitoException.class,
                () -> service.end(b, "2027-04-01")).code());
        service.end(ward, "2027-04-01");
        assertEquals(List.of(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 1)),
                store.links.stream().map(link -> link.period().to()).toList());

        assertEquals(b, service.move(ward, b, "2026-10-01", "matrix").parentId());
        FuhitoException unknown = assertThrows(FuhitoException.class,
                () -> service.move(ward, b, "2026-04-01", "nope"));
        assertEquals(FuhitoException.Reason.INVALID, unknown.reason());
        assertEquals("hierarchy.unknown", unknown.code());
    }

    @Test
    void readsTheTreeOfASecondHierarchy() {
        store.hierarchyCodes.put("matrix-id", "matrix");
        String line = root("Line");
        String project = root("Project");
        root("Alone");
        String team = service.create(named("Team", line, "2026-04-01")).unit().id();
        String squad = service.create(named("Squad", team, "2026-04-01")).unit().id();
        service.move(team, project, "2026-04-01", "matrix");
        Page all = Page.of(null, null);
        String on = "2026-04-01";

        // Only the units with a parent or a child in the matrix are in it.
        assertEquals(List.of("Project", "Team"), names(service.list(on, false, "matrix", all)));
        assertEquals(List.of("Project"), names(service.list(on, true, "matrix", all)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"), names(service.list(on, false, null, all)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"),
                names(service.list(on, false, "default", all)));

        assertEquals(List.of("Team"), names(service.children(project, on, "matrix", all)));
        assertEquals(List.of(), names(service.children(project, on, null, all)));
        assertEquals(List.of("Project", "Team"), names(service.subtree(project, on, "matrix", all)));
        assertEquals(List.of("Team"), names(service.subtree(team, on, "matrix", Page.of(0, 5))));
        assertEquals(List.of("Project", "Team"), names(service.path(team, on, "matrix")));
        assertEquals(List.of("Line", "Team"), names(service.path(team, on)));
        assertEquals(project, service.get(team, on, "matrix").parentId());
        assertEquals(line, service.get(team, on).parentId());
        assertNull(service.get(squad, on, "matrix").parentId());

        assertEquals("hierarchy.unknown",
                assertThrows(FuhitoException.class, () -> service.list(on, false, "nope", all)).code());
        assertEquals("hierarchy.unknown",
                assertThrows(FuhitoException.class, () -> service.path(team, on, "nope")).code());
    }

    @Test
    void anOrgUnitIsALegalEntityWhenItsUnitTypeSaysSo() {
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", "Company", Map.of(), 0, Map.of(),
                true, true));
        store.legalEntityTypes.add("type-company");
        unitType("department", true);
        String holding = service.create(new NewOrgUnit(null, "Holding", null, null, null, null, "company", null,
                "2026-04-01", 2, null, null, null)).unit().id();
        String subsidiary = service.create(new NewOrgUnit(null, "Subsidiary", null, null, null, null, "company",
                holding, "2026-04-01", 1, null, null, null)).unit().id();
        String sales = service.create(new NewOrgUnit(null, "Sales", null, null, null, null, "department", holding,
                "2026-04-01", null, null, null, null)).unit().id();
        root("Untyped");

        assertTrue(service.get(holding, "2026-04-01").legalEntity());
        assertFalse(service.get(sales, "2026-04-01").legalEntity());
        // Untyped, then Holding with Sales and Subsidiary below it.
        assertEquals(List.of(false, true, false, true), service.list("2026-04-01", false, null, Page.of(null, null))
                .stream().map(OrgUnitState::legalEntity).toList());
        assertEquals(List.of(true), service.history(holding).versions().stream().map(OrgUnitState::legalEntity).toList());

        List<OrgUnitState> entities = service.legalEntities("2026-04-01", Page.of(null, null));
        assertEquals(List.of("Subsidiary", "Holding"), names(entities));
        assertEquals(holding, entities.getFirst().parentId());
        assertEquals(List.of("Holding"), names(service.legalEntities("2026-04-01", Page.of(1, 1))));
        assertEquals(List.of(), service.legalEntities("2026-03-31", Page.of(null, null)));

        // The type of a unit is dated, so a unit becomes a legal entity on a date.
        service.changeDetails(sales, new OrgUnitChange("2027-04-01", "Sales Inc.", null, null, null, null, "company",
                null, null));
        assertFalse(service.get(sales, "2027-03-31").legalEntity());
        assertTrue(service.get(sales, "2027-04-01").legalEntity());
        assertEquals(subsidiary, service.legalEntities("2027-04-01", Page.of(null, null)).get(1).unit().id());
        assertEquals(3, service.legalEntities("2027-04-01", Page.of(null, null)).size());
    }

    @Test
    void anEmployerStaysALegalEntityAndDoesNotEndWhileEmploymentsNameIt() {
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", "Company", Map.of(), 0, Map.of(),
                true, true));
        unitType("department", true);
        MemoryEmploymentStore employments = new MemoryEmploymentStore();
        store.employments = employments;
        String company = service.create(new NewOrgUnit(null, "Company", null, null, null, null, "company", null,
                "2020-04-01", null, null, null, null)).unit().id();
        employments.insert(new Employment("e1", "alice", null, company,
                new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2028, 4, 1)), null, null, null, null));
        OrgUnitChange toDepartment = new OrgUnitChange("2026-01-01", "Company", null, null, null, null, "department",
                null, null);

        // The employment starts after the change, so the company would not be a legal entity on its first date.
        FuhitoException changed = assertThrows(FuhitoException.class, () -> service.changeDetails(company, toDepartment));
        assertEquals(FuhitoException.Reason.CONFLICT, changed.reason());
        assertEquals("org_unit.is_employer", changed.code());
        assertEquals("1", changed.params().get("count"));
        assertEquals("org_unit.is_employer", assertThrows(FuhitoException.class, () -> service.changeDetails(company,
                new OrgUnitChange("2026-01-01", "Company", null, null, null, null, null, null, null))).code());
        // A change that keeps the type of a legal entity works, and so does a change after the first date.
        service.changeDetails(company, new OrgUnitChange("2026-01-01", "Company Inc.", null, null, null, null,
                "company", null, null));
        service.changeDetails(company, new OrgUnitChange("2027-01-01", "Company", null, null, null, null,
                "department", null, null));

        FuhitoException ended = assertThrows(FuhitoException.class, () -> service.end(company, "2028-03-31"));
        assertEquals(FuhitoException.Reason.CONFLICT, ended.reason());
        assertEquals("org_unit.has_employments", ended.code());
        assertEquals("1", ended.params().get("count"));
        // The company can end on the day after the last day of the employment.
        assertEquals("Company", service.end(company, "2028-04-01").version().name());
    }

    @Test
    void readsTheHistoryOfAUnit() {
        unitType("ward", true);
        String a = root("A");
        String b = root("B");
        String id = service.create(new NewOrgUnit("w", "Ward", null, null, null, null, "ward", a, "2026-04-01", null,
                null, "X-1", "hr")).unit().id();
        service.changeDetails(id, rename("2026-10-01", "Ward 2"));
        service.move(id, null, "2027-04-01");
        service.move(id, b, "2028-04-01");

        OrgUnitHistory history = service.history(id);

        assertEquals("w", history.unit().code());
        assertEquals(List.of("Ward", "Ward 2"), names(history.versions()));
        assertEquals("ward", history.versions().getFirst().unitType());
        assertNull(history.versions().get(1).unitType());
        assertNull(history.versions().getFirst().parentId());
        assertEquals(LocalDate.of(2026, 10, 1), history.versions().getFirst().version().period().to());
        assertEquals(List.of(a, b), history.parents().stream().map(OrgUnitLink::parentId).toList());
        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), history.parents().getFirst().period());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.history("nope")).code());
    }

    @Test
    void readsAUnitAsOfADate() {
        unitType("ward", true);
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();
        OrgUnitState created = service.create(new NewOrgUnit(null, "Ward", null, null, null, null, "ward", parentId,
                "2026-05-01", null, null, null, null));

        assertEquals(created, service.get(created.unit().id(), "2026-05-01"));
        assertEquals(created, service.get(created.unit().id(), "2030-01-01"));
    }

    @Test
    void todayFollowsTheClock() {
        String startsToday = service.create(named("A", null, "2026-06-15")).unit().id();
        String startsTomorrow = service.create(named("B", null, "2026-06-16")).unit().id();

        assertEquals("A", service.get(startsToday, null).version().name());
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(startsTomorrow, null));
        assertEquals("org_unit.not_valid_on", e.code());
        assertEquals(Map.of("id", startsTomorrow, "date", "2026-06-15"), e.params());
    }

    @Test
    void doesNotReadAUnitBeforeItStartsOrAnUnknownUnit() {
        String id = service.create(named("A", null, "2026-04-01")).unit().id();

        FuhitoException early = assertThrows(FuhitoException.class, () -> service.get(id, "2026-03-31"));
        assertEquals(FuhitoException.Reason.NOT_FOUND, early.reason());
        assertEquals("org_unit.not_valid_on", early.code());

        FuhitoException unknown = assertThrows(FuhitoException.class, () -> service.get("nope", "2026-04-01"));
        assertEquals(FuhitoException.Reason.NOT_FOUND, unknown.reason());
        assertEquals("org_unit.not_found", unknown.code());

        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> service.get(id, "x")).code());
    }

    @Test
    void readsAUnitWhoseUnitTypeWasRemoved() {
        store.insertUnit(new OrgUnit("u1", null, null, null, null));
        store.versions.add(new OrgUnitVersion("v1", "u1", ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), "U",
                Map.of(), null, Map.of(), null, "gone", 0, Map.of()));

        assertNull(service.get("u1", "2026-04-01").unitType());
    }
}

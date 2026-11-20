package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUnitServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    // 2026-06-15 in Tokyo. It is still 2026-06-14 in UTC.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-14T16:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignments = new MemoryAssignmentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final OrgUnitService service = new OrgUnitService(store, externalIds, assignments, refItems, CLOCK);

    private static NewOrgUnit named(String name, String parentId, String validFrom) {
        return new NewOrgUnit(null, "ja", ja(name), null, parentId, validFrom, null, null, null, null);
    }

    private FuhitoException failure(NewOrgUnit request) {
        return assertThrows(FuhitoException.class, () -> service.create(request));
    }

    private static OrgUnitService.Search search(String q, String code, String unitType, String asOf) {
        return new OrgUnitService.Search(q, code, unitType, null, null, null, false, null, asOf);
    }

    private static OrgUnitService.Search roots(String asOf, String hierarchy, Boolean lateral) {
        return new OrgUnitService.Search(null, null, null, lateral, null, null, true, hierarchy, asOf);
    }

    private static OrgUnitService.Search legalEntities(String asOf) {
        return new OrgUnitService.Search(null, null, null, null, null, true, false, null, asOf);
    }

    private RefItem unitType(String code, boolean active) {
        RefItem item = new RefItem("type-" + code, RefKind.UNIT_TYPE, code, ItemNames.of(code), 0, Map.of(), active);
        refItems.insert(item);
        return item;
    }

    private String unitOfType(String name, String unitType, String parentId) {
        return service.create(new NewOrgUnit(null, "ja", ja(name), unitType, parentId, "2026-04-01",
                null, null, null, null)).unit().id();
    }

    @Test
    void readsTheVersionOfAUnit() {
        String hospital = service.create(named("X病院", null, "2026-04-01")).unit().id();

        assertEquals(service.history(hospital).unit().version(), service.version(hospital));
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.version("nope")).code());
    }

    @Test
    void searchesUnitsAtAnyDepthByNameCodeAndType() {
        RefItem ward = unitType("ward", true);
        String hospital = service.create(named("X病院", null, "2026-04-01")).unit().id();
        service.create(new NewOrgUnit("W1", "ja", Map.of("ja", new OrgUnitName("1病棟", null, "いちびょうとう"),
                "en", new OrgUnitName("Ward One", "W1", "ward one")), "ward", hospital, "2026-04-01", null, null,
                null, null));
        service.create(new NewOrgUnit("W2", "ja", Map.of("ja", new OrgUnitName("2病棟", "二病", null)), "ward",
                hospital, "2026-04-01", null, null, null, null));
        Page all = Page.of(null, null);

        assertEquals(List.of("1病棟", "2病棟"), names(service.list(search("病棟", null, null, null), all, null)));
        assertEquals(List.of("1病棟"), names(service.list(search("ward ONE", null, null, null), all, null)));
        assertEquals(List.of("1病棟"), names(service.list(search("いち", null, null, null), all, null)));
        assertEquals(List.of("2病棟"), names(service.list(search("二病", null, null, null), all, null)));
        assertEquals(List.of("2病棟"), names(service.list(search(null, "W2", null, null), all, null)));
        assertEquals(2, service.count(search(null, null, "ward", null)));
        assertEquals(3, service.count(search(" ", null, null, null)));
        // A unit with a phonetic name comes before the units with none.
        assertEquals(List.of("1病棟"), names(service.list(search(null, null, null, null), Page.of(0, 1), null)));
        assertEquals(0, service.count(search("病棟", null, null, "2026-03-31")));
        assertEquals(ward.id(), store.versions(service.list(search(null, "W1", null, null), all, null).getFirst()
                .unit().id()).getFirst().unitTypeId());
        assertEquals("org_unit.unit_type_unknown", assertThrows(FuhitoException.class,
                () -> service.list(search(null, null, "room", null), all, null)).code());
    }

    @Test
    void aLateralUnitMaySitAnywhereButNoLineUnitIsBelowIt() {
        store.refItems = refItems;
        refItems.insert(new RefItem("type-committee", RefKind.UNIT_TYPE, "committee", ItemNames.of("committee"), 0,
                Map.of(), true, false, false, true));
        unitType("department", true);
        String hospital = unitOfType("Hospital", null, null);
        String committee = unitOfType("Safety", "committee", hospital);
        String sub = unitOfType("Drugs", "committee", committee);
        String union = unitOfType("Union", "committee", null);
        Page all = Page.of(null, null);

        assertTrue(service.get(committee, null).lateral());
        assertFalse(service.get(hospital, null).lateral());
        assertEquals(List.of("Hospital", "Union"), names(service.list(roots(null, null, null), all, null)));
        assertEquals(List.of("Hospital"), names(service.list(roots(null, null, false), all, null)));
        assertEquals(List.of("Union"), names(service.list(roots(null, null, true), all, null)));
        assertEquals(List.of("Safety", "Drugs"), names(service.subtree(hospital, null, null, true, all)));
        assertEquals(List.of("Hospital"), names(service.subtree(hospital, null, null, false, all)));
        assertEquals(List.of(), names(service.children(hospital, null, null, false, all)));

        // A line unit is never below a lateral unit, whichever write would put it there.
        assertEquals("org_unit.line_below_lateral", failure(named("Ward", committee, "2026-05-01")).code());
        String ward = unitOfType("Ward", "department", hospital);
        assertEquals("org_unit.line_below_lateral",
                assertThrows(FuhitoException.class, () -> service.move(ward, committee, "2026-07-01")).code());
        assertEquals("org_unit.line_below_lateral", assertThrows(FuhitoException.class,
                () -> service.changeDetails(hospital, new OrgUnitChange("2026-08-01", null, "ja", ja("Hospital"), "committee", null, null))).code());
        assertEquals("org_unit.line_below_lateral", assertThrows(FuhitoException.class,
                () -> service.changeDetails(sub, new OrgUnitChange("2026-08-01", null, "ja", ja("Drugs"),
                        "department", null, null))).code());
        assertEquals(List.of("Hospital", "Union"), names(service.list(roots("2026-09-01", null, null), all, null)));

        // A lateral unit can go below a line unit or another lateral.
        service.move(union, hospital, "2026-09-01");
        assertEquals(List.of("Hospital"), names(service.list(roots("2026-09-01", null, null), all, null)));

        // A unit that was a lateral unit before a date can take a line unit below it from that date.
        String board = unitOfType("Board", "committee", null);
        service.changeDetails(board, new OrgUnitChange("2026-10-01", null, "ja", ja("Board"), null,
                null, null));
        service.create(named("Office", board, "2026-10-01"));
    }

    private void rollupTypes() {
        store.refItems = refItems;
        refItems.insert(new RefItem("type-region", RefKind.UNIT_TYPE, "region", ItemNames.of("region"), 0,
                Map.of(), true, false, false, false, true));
        unitType("branch", true);
    }

    private static OrgUnitService.Search rollups(Boolean rollup, boolean roots) {
        return new OrgUnitService.Search(null, null, null, null, rollup, null, roots, null, null);
    }

    @Test
    void aRollupUnitGathersUnitsInAnotherHierarchyAndIsNotInTheDefaultOne() {
        rollupTypes();
        store.hierarchyCodes.put("region-id", "region");
        String company = unitOfType("Company", null, null);
        String tokyo = unitOfType("Tokyo", "branch", company);
        String kanto = unitOfType("Kanto", "region", null);
        service.move(tokyo, kanto, "2026-04-01", "region");
        Page all = Page.of(null, null);

        OrgUnitState read = service.get(kanto, null);
        assertTrue(read.rollup());
        assertFalse(read.lateral());
        assertNull(read.parentId());
        assertFalse(service.get(tokyo, null).rollup());
        // The reads of the default tree leave it out. The list of every unit has it.
        assertEquals(List.of("Company"), names(service.list(roots(null, null, null), all, null)));
        assertEquals(1, service.count(roots(null, null, null)));
        assertEquals(List.of(), names(service.list(rollups(true, true), all, null)));
        assertEquals(List.of("Company"), names(service.list(rollups(false, true), all, null)));
        assertEquals(List.of("Company", "Kanto", "Tokyo"), names(service.list(rollups(null, false), all, null)));
        assertEquals(List.of("Kanto"), names(service.list(rollups(true, false), all, null)));
        assertEquals(2, service.count(rollups(false, false)));
        assertEquals(List.of(), service.subtree(kanto, null, null, null, all));
        assertEquals(0, service.countSubtree(kanto, null, null, null));
        assertEquals(0, service.countSubtree(kanto, null, "default", false));
        assertEquals(List.of(), service.path(kanto, null, null));
        assertEquals(List.of(), service.path(kanto, null, "default"));
        assertEquals(List.of(), service.children(kanto, null, null, null, all));
        assertEquals(List.of("Company", "Tokyo"), names(service.subtree(company, null, null, null, all)));
        assertEquals(List.of("Company", "Tokyo"), names(service.path(tokyo, null, null)));
        // In the other hierarchy, it is a root with its children.
        assertEquals(List.of("Kanto"), names(service.list(roots(null, "region", null), all, null)));
        assertEquals(List.of("Kanto", "Tokyo"), names(service.subtree(kanto, null, "region", null, all)));
        assertEquals(2, service.countSubtree(kanto, null, "region", null));
        assertEquals(List.of("Kanto", "Tokyo"), names(service.path(tokyo, null, "region")));
        assertEquals(List.of("Kanto"), names(service.path(kanto, null, "region")));
    }

    @Test
    void aRollupUnitHasNoParentAndNoChildInTheDefaultHierarchy() {
        rollupTypes();
        String company = unitOfType("Company", null, null);
        String tokyo = unitOfType("Tokyo", "branch", company);
        String kanto = unitOfType("Kanto", "region", null);

        FuhitoException below = failure(new NewOrgUnit(null, "ja", ja("Kansai"), "region", company, "2026-05-01",
                null, null, null, null));
        assertEquals("org_unit.rollup_has_parent", below.code());
        assertEquals(company, below.params().get("parentId"));
        assertEquals("2026-05-01", below.params().get("date"));
        FuhitoException above = failure(named("Yokohama", kanto, "2026-05-01"));
        assertEquals("org_unit.rollup_has_child", above.code());
        assertEquals(kanto, above.params().get("parentId"));
        assertEquals(Map.of("id", kanto, "parentId", company, "date", "2026-07-01"),
                assertThrows(FuhitoException.class, () -> service.move(kanto, company, "2026-07-01")).params());
        assertEquals("org_unit.rollup_has_child",
                assertThrows(FuhitoException.class, () -> service.move(tokyo, kanto, "2026-07-01")).code());
        // Taking away a parent that it does not have changes nothing.
        assertNull(service.move(kanto, null, "2026-07-01").parentId());

        // A unit cannot get a rollup type from a date on which it has a parent or a child.
        assertEquals(Map.of("id", tokyo, "parentId", company, "date", "2026-08-01"), assertThrows(
                FuhitoException.class, () -> service.changeDetails(tokyo, new OrgUnitChange("2026-08-01", null, "ja",
                        ja("Tokyo"), "region", null, null))).params());
        FuhitoException parent = assertThrows(FuhitoException.class, () -> service.changeDetails(company,
                new OrgUnitChange("2026-08-01", null, "ja", ja("Company"), "region", null, null)));
        assertEquals("org_unit.rollup_has_child", parent.code());
        assertEquals(Map.of("id", tokyo, "parentId", company, "date", "2026-08-01"), parent.params());
        // A child that comes later in the period of the new type fails too, from its date.
        String later = unitOfType("Later", "branch", null);
        String child = unitOfType("Child", "branch", null);
        service.move(child, later, "2026-10-01");
        FuhitoException fromLater = assertThrows(FuhitoException.class, () -> service.changeDetails(later,
                new OrgUnitChange("2026-08-01", null, "ja", ja("Later"), "region", null, null)));
        assertEquals("org_unit.rollup_has_child", fromLater.code());
        assertEquals("2026-10-01", fromLater.params().get("date"));
        service.move(child, null, "2026-10-01");
        assertTrue(service.changeDetails(later, new OrgUnitChange("2026-08-01", null, "ja", ja("Later"), "region",
                null, null)).rollup());
    }

    @Test
    void aUnitThatLeavesARollupTypeIsARootOfTheDefaultHierarchyFromThatDate() {
        rollupTypes();
        String company = unitOfType("Company", null, null);
        String kanto = unitOfType("Kanto", "region", null);
        Page all = Page.of(null, null);

        OrgUnitState left = service.changeDetails(kanto, new OrgUnitChange("2026-10-01", null, "ja", ja("Kanto"),
                "branch", null, null));

        assertFalse(left.rollup());
        assertEquals(List.of("Company"), names(service.list(roots("2026-09-30", null, null), all, null)));
        assertEquals(List.of("Company", "Kanto"), names(service.list(roots("2026-10-01", null, null), all, null)));
        assertEquals(List.of("Kanto"), names(service.path(kanto, "2026-10-01", null)));
        // From that date it can get a parent, but not before.
        assertEquals("org_unit.rollup_has_parent",
                assertThrows(FuhitoException.class, () -> service.move(kanto, company, "2026-09-01")).code());
        assertEquals(company, service.move(kanto, company, "2026-10-01").parentId());
        assertEquals(List.of("Company", "Kanto"), names(service.subtree(company, "2026-10-01", null, null, all)));
    }

    @Test
    void aLaterEndDoesNotPutAUnitBelowARollupUnit() {
        rollupTypes();
        String area = unitOfType("Area", "branch", null);
        String shop = unitOfType("Shop", "branch", area);
        service.end(shop, "2026-10-01");
        service.changeDetails(area, new OrgUnitChange("2026-10-01", null, "ja", ja("Area"), "region", null, null));

        assertEquals("org_unit.rollup_has_child",
                assertThrows(FuhitoException.class, () -> service.removeEnd(shop)).code());
        assertEquals("org_unit.rollup_has_child",
                assertThrows(FuhitoException.class, () -> service.end(shop, "2026-11-01")).code());
    }

    @Test
    void aRollupUnitTakesNoWorkLocationOrCostCenterFromAbove() {
        rollupTypes();
        store.hierarchyCodes.put("region-id", "region");
        place(RefKind.WORK_LOCATION, "tokyo", true);
        place(RefKind.WORK_LOCATION, "japan", true);
        place(RefKind.COST_CENTER, "cc-hq", true);
        String company = placed("Company", null, "tokyo", "cc-hq");
        String japan = service.create(new NewOrgUnit(null, "ja", ja("Japan"), "region", "japan", null, null,
                "2026-04-01", null, null, null, null)).unit().id();
        String kanto = unitOfType("Kanto", "region", null);
        String branch = unitOfType("Tokyo", "branch", company);
        service.move(kanto, japan, "2026-04-01", "region");
        service.move(branch, kanto, "2026-04-01", "region");
        Page all = Page.of(null, null);

        assertEquals("japan null japan null", places(service.get(japan, null)));
        assertEquals("null null null null", places(service.get(kanto, null, "region")));
        // A unit that it gathers keeps the values that the default hierarchy gives.
        assertEquals(List.of("japan null japan null", "null null null null", "null null tokyo cc-hq"),
                service.subtree(japan, null, "region", null, all).stream().map(OrgUnitServiceTest::places).toList());
    }

    @Test
    void createsARootWithOnlyANameAndADate() {
        OrgUnitState state = service.create(named("Clinic", null, "2026-04-01"));

        assertEquals(36, state.unit().id().length());
        assertNull(state.version().code());
        assertNull(state.unit().keycloakOrganizationId());
        assertNull(state.parentId());
        assertNull(state.unitType());
        OrgUnitVersion version = state.version();
        assertEquals(state.unit().id(), version.orgUnitId());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), version.period());
        assertEquals(Localized.of("ja", new OrgUnitName("Clinic", null, null)), version.names());
        assertNull(version.unitTypeId());
        assertEquals(0, version.displayOrder());
        assertEquals(Map.of(), version.attributes());
        assertTrue(store.links.isEmpty());
    }

    @Test
    void createsAChildWithEveryValue() {
        RefItem department = unitType("department", true);
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();

        OrgUnitState state = service.create(new NewOrgUnit("naika", "JA", Map.of("ja",
                new OrgUnitName(" 内科 ", "内", "ないか"), "en-us", new OrgUnitName("Internal Medicine", "IM", null)),
                "department", parentId, "2026-04-01", 3, Map.of("floor", 2), "D-001", "hr-system"));

        assertEquals("naika", state.version().code());
        assertEquals(Optional.of(state.unit().id()), externalIds.idOf(ExternalId.Kind.ORG_UNIT, "hr-system", "D-001"));
        assertEquals("hr-system", state.unit().source());
        assertEquals("department", state.unitType());
        assertEquals(parentId, state.parentId());
        OrgUnitVersion version = state.version();
        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("内科", "内", "ないか"),
                "en-US", new OrgUnitName("Internal Medicine", "IM", null))), version.names());
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
        assertEquals("invalid.code", failure(new NewOrgUnit("bad code", "ja", ja("x"), null, null,
                "2026-04-01", null, null, null, null)).code());
        assertTrue(store.units.isEmpty());
    }

    @Test
    void aCodeChangesOnADateAndIsFreeForAnotherUnitFromThere() {
        String old = service.create(new NewOrgUnit("A100", "ja", ja("Old"), null, null, "2026-04-01",
                null, null, null, null)).unit().id();
        service.changeDetails(old, new OrgUnitChange("2027-04-01", "B100", "ja", ja("Old"), null,
                null, null));

        String next = service.create(new NewOrgUnit("A100", "ja", ja("Next"), null, null,
                "2027-04-01", null, null, null, null)).unit().id();
        assertEquals("org_unit.code_taken", failure(new NewOrgUnit("A100", "ja", ja("Early"), null,
                null, "2027-03-01", null, null, null, null)).code());
        assertEquals("org_unit.code_taken", assertThrows(FuhitoException.class, () -> service.changeDetails(old,
                new OrgUnitChange("2028-04-01", "A100", "ja", ja("Old"), null, null,
                null))).code());
        assertEquals(old, store.findUnitByCode("A100", LocalDate.of(2027, 3, 31)).orElseThrow().id());
        assertEquals(next, store.findUnitByCode("A100", LocalDate.of(2027, 4, 1)).orElseThrow().id());
        assertEquals("B100", service.get(old, "2027-04-01").version().code());
    }

    @Test
    void aLongerPeriodKeepsTheCodeFree() {
        String old = service.create(new NewOrgUnit("SALES", "ja", ja("Sales"), null, null,
                "2026-04-01", null, null, null, null)).unit().id();
        service.end(old, "2027-04-01");
        service.create(new NewOrgUnit("SALES", "ja", ja("New sales"), null, null, "2027-04-01",
                null, null, null, null));

        assertEquals("org_unit.code_taken", endFailure(old, "2027-05-01").code());
        assertEquals("org_unit.code_taken", assertThrows(FuhitoException.class, () -> service.removeEnd(old)).code());
        assertEquals("2027-04-01", service.get(old, "2027-03-31").version().period().to().toString());
    }

    @Test
    void rejectsAnExternalIdThatIsTakenOrBad() {
        service.create(new NewOrgUnit(null, "ja", ja("Ward"), null, null, "2026-04-01", null, null,
                "H-1", "hr"));

        assertEquals("external_id.taken", failure(new NewOrgUnit(null, "ja", ja("Other"), null,
                null, "2026-04-01", null, null, "H-1", "hr")).code());
        assertEquals("invalid.code", failure(new NewOrgUnit(null, "ja", ja("Other"), null, null,
                "2026-04-01", null, null, "H-2", "bad source")).code());
        assertEquals("external_id.bad_value", failure(new NewOrgUnit(null, "ja", ja("Other"), null,
                null, "2026-04-01", null, null, " ", "hr")).code());
        assertEquals("external_id.bad_value", failure(new NewOrgUnit(null, "ja", ja("Other"), null,
                null, "2026-04-01", null, null, "x".repeat(256), "hr")).code());
        // The same ID of another system is free.
        service.create(new NewOrgUnit(null, "ja", ja("Other"), null, null, "2026-04-01", null, null,
                "H-1", "payroll"));
    }

    @Test
    void rejectsACodeThatIsTaken() {
        NewOrgUnit request = new NewOrgUnit("hq", "ja", ja("x"), null, null, "2026-04-01", null, null,
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
            FuhitoException e = failure(new NewOrgUnit(null, "ja", ja("x"), unitType, null,
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
                LocalDate.of(2027, 4, 1)), null, Localized.of("ja", new OrgUnitName("Closing", null, null)), null,
                null, null, 0, Map.of()));

        assertEquals("org_unit.parent_not_valid", failure(named("Ward", "closing", "2026-04-01")).code());
    }

    private static OrgUnitChange rename(String validFrom, String name) {
        return new OrgUnitChange(validFrom, null, "ja", ja(name), null, null, null);
    }

    @Test
    void aChangeSplitsTheDetailsAtItsDate() {
        String id = service.create(named("Old", null, "2026-04-01")).unit().id();

        OrgUnitState changed = service.changeDetails(id, rename("2026-10-01", "New"));

        assertEquals(id, changed.unit().id());
        assertEquals("New", changed.version().names().primary().name());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 10, 1)), changed.version().period());
        assertEquals("Old", service.get(id, "2026-09-30").version().names().primary().name());
        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1)),
                service.get(id, "2026-09-30").version().period());
        assertEquals("New", service.get(id, "2026-10-01").version().names().primary().name());
    }

    @Test
    void aChangeInsideOlderDetailsKeepsTheLaterOnes() {
        String id = service.create(named("A", null, "2026-04-01")).unit().id();
        service.changeDetails(id, rename("2027-04-01", "C"));

        OrgUnitState changed = service.changeDetails(id, rename("2026-10-01", "B"));

        assertEquals(new ValidPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)), changed.version().period());
        assertEquals("A", service.get(id, "2026-09-30").version().names().primary().name());
        assertEquals("B", service.get(id, "2027-03-31").version().names().primary().name());
        assertEquals("C", service.get(id, "2027-04-01").version().names().primary().name());
        assertEquals(3, store.versions(id).size());
    }

    @Test
    void aChangeOnTheFirstDateOfDetailsCorrectsThem() {
        String id = service.create(named("Typo", null, "2026-04-01")).unit().id();
        String versionId = store.versions(id).getFirst().id();

        OrgUnitState changed = service.changeDetails(id, rename("2026-04-01", "Fixed"));

        assertEquals(versionId, changed.version().id());
        assertEquals("Fixed", changed.version().names().primary().name());
        assertEquals(1, store.versions(id).size());
    }

    @Test
    void aChangeReplacesAllDetails() {
        unitType("ward", true);
        RefItem clinic = unitType("clinic", true);
        String parentId = service.create(named("Hospital", null, "2026-04-01")).unit().id();
        String id = service.create(new NewOrgUnit("w1", "en", Map.of("en", new OrgUnitName("Ward", "W", null),
                "ja", new OrgUnitName("病棟", "病", "びょうとう")), "ward", parentId, "2026-04-01", 9,
                Map.of("beds", 30), "X-1", "hr")).unit().id();

        OrgUnitState changed = service.changeDetails(id, new OrgUnitChange("2026-10-01", "c1", "ja",
                Map.of("en", new OrgUnitName("Clinic", "C", null), "ja", new OrgUnitName("診療所", "診", "しんりょうじょ")),
                "clinic", 2, Map.of("rooms", 3)));

        OrgUnitVersion v = changed.version();
        assertEquals(new Localized<>("ja", Map.of("en", new OrgUnitName("Clinic", "C", null),
                "ja", new OrgUnitName("診療所", "診", "しんりょうじょ"))), v.names());
        assertEquals("Ward", service.get(id, "2026-09-30").version().names().primary().name());
        assertEquals(clinic.id(), v.unitTypeId());
        assertEquals("clinic", changed.unitType());
        assertEquals(2, v.displayOrder());
        assertEquals(Map.of("rooms", 3), v.attributes());
        assertEquals("c1", v.code());
        assertEquals("w1", service.get(id, "2026-09-30").version().code());
        // Not details, so they stay.
        assertEquals(Optional.of(id), externalIds.idOf(ExternalId.Kind.ORG_UNIT, "hr", "X-1"));
        assertEquals("hr", changed.unit().source());
        assertEquals(parentId, changed.parentId());

        OrgUnitState cleared = service.changeDetails(id, rename("2027-04-01", "Bare"));
        assertNull(cleared.version().names().primary().shortName());
        assertNull(cleared.version().names().primary().phoneticName());
        assertNull(cleared.unitType());
        assertEquals(Map.of(), cleared.version().attributes());
        assertEquals(0, cleared.version().displayOrder());
    }

    @Test
    void aChangeMayKeepADeactivatedUnitTypeButNotTakeANewOne() {
        RefItem ward = unitType("ward", true);
        unitType("retired", false);
        String id = service.create(new NewOrgUnit(null, "ja", ja("Ward"), "ward", null, "2026-04-01",
                null, null, null, null)).unit().id();
        refItems.update(new RefItem(ward.id(), RefKind.UNIT_TYPE, "ward", ItemNames.of("ward"), 0, Map.of(), false));

        assertEquals("ward", service.changeDetails(id, new OrgUnitChange("2026-10-01", null, "ja", ja("Ward 2"),
                "ward", null, null)).unitType());
        for (String unitType : new String[] {"retired", "nope"}) {
            assertEquals("org_unit.unit_type_unknown", assertThrows(FuhitoException.class,
                    () -> service.changeDetails(id, new OrgUnitChange("2027-04-01", null, "ja", ja("x"),
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
        assertEquals("A", service.get(id, "2026-10-01").version().names().primary().name());
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
        store.insertUnit(new OrgUnit("ending", null, null));
        store.versions.add(new OrgUnitVersion("v1", "ending", new ValidPeriod(LocalDate.of(2026, 4, 1),
                LocalDate.of(2027, 4, 1)), null, Localized.of("ja", new OrgUnitName("Ending", null, null)), null,
                null, null, 0, Map.of()));

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

        assertEquals("Old name", service.get(unit, "2026-09-30").version().names().primary().name());
        assertEquals("New name", service.get(unit, "2027-03-31").version().names().primary().name());
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
    void doesNotEndAUnitBeforeLaterChanges() {
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
        assertEquals("org_unit.later_changes_exist", endFailure(unit, "2028-04-01").code());
    }

    @Test
    void theEndOfAUnitMovesAndGoesAwayWithItsParent() {
        String a = root("A");
        String unit = child("Unit", a);
        service.end(unit, "2029-04-01");
        service.end(a, "2030-04-01");

        // The same end changes nothing. An earlier one cuts the parent link too.
        assertEquals("2029-04-01", service.end(unit, "2029-04-01").version().period().to().toString());
        service.end(unit, "2028-04-01");
        assertEquals(List.of("2028-04-01"), store.parentLinks(unit).stream()
                .map(link -> link.period().to().toString()).toList());

        // A later one lengthens the link, as far as the parent exists.
        assertEquals("org_unit.parent_not_valid", endFailure(unit, "2031-04-01").code());
        service.end(unit, "2030-04-01");
        assertEquals(List.of("2030-04-01"), store.parentLinks(unit).stream()
                .map(link -> link.period().to().toString()).toList());
        assertEquals("org_unit.parent_not_valid", assertThrows(FuhitoException.class,
                () -> service.removeEnd(unit)).code());
        service.removeEnd(a);
        assertNull(service.removeEnd(unit).version().period().to());
        assertTrue(store.parentLinks(unit).getFirst().period().isOpenEnded());
        assertNull(service.removeEnd(unit).version().period().to());

        // A unit that others follow keeps its end.
        String next = root("Next");
        service.succeed(unit, "2031-04-01", List.of(next));
        assertEquals("org_unit.has_successors", endFailure(unit, "2032-04-01").code());
        assertEquals("org_unit.has_successors", assertThrows(FuhitoException.class,
                () -> service.removeEnd(unit)).code());
        assertEquals("org_unit.already_ended", assertThrows(FuhitoException.class,
                () -> service.succeed(unit, "2032-04-01", List.of(next))).code());
        // The unit that follows must exist on the date of the succession.
        assertEquals("org_unit.follows_others", endFailure(next, "2031-04-01").code());
        assertEquals("org_unit.follows_others", endFailure(next, "2030-06-01").code());
        assertEquals("2031-04-02", service.end(next, "2031-04-02").version().period().to().toString());
    }

    private void assign(String assignmentId, String orgUnitId, String from, String to) {
        assignments.insert(new Assignment(assignmentId + "-" + from, assignmentId, "employment", orgUnitId,
                new ValidPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), null, null, null,
                null, null, false, false, null, 0, Map.of(), null, null, 1));
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
        return service.create(new NewOrgUnit(null, "ja", Map.of("ja", new OrgUnitName(name, null, phoneticName)),
                null, parentId, "2026-04-01", displayOrder, null, null, null)).unit().id();
    }

    private static List<String> names(List<OrgUnitState> states) {
        return states.stream().map(s -> s.version().names().primary().name()).toList();
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
                names(Trees.all(service, "2026-04-01", null)));
        assertEquals(List.of("X Hospital", "Y Hospital"), names(service.list(roots("2026-04-01", null, null), Page.of(null, null), null)));
        assertEquals(List.of("Medicine", "Nursing"), names(service.children(x, "2026-04-01", null, null, Page.of(null, null))));
        assertEquals(List.of("Admin"), names(service.children(y, "2026-04-01", null, null, Page.of(null, null))));
        assertEquals(List.of("Nursing", "Ward 1", "Ward 2"), names(service.subtree(nursing, "2026-04-01", null, null, Page.of(null, null))));
        assertEquals(nursing, service.subtree(nursing, "2026-04-01", null, null, Page.of(null, null)).get(1).parentId());
        assertEquals(List.of("Nursing", "Ward 1"), names(service.subtree(x, "2026-04-01", null, null, Page.of(2, 2))));
        assertEquals(List.of("Y Hospital"), names(service.list(roots("2026-04-01", null, null), Page.of(1, 5), null)));
        assertEquals(List.of("Nursing"), names(service.children(x, "2026-04-01", null, null, Page.of(1, 1))));
        assertEquals(List.of("Ward 2"), names(service.subtree(nursing, "2026-04-01", null, null, Page.of(2, 1))));
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
                names(service.children(root, "2026-04-01", null, null, Page.of(null, null))));
    }

    @Test
    void listsEveryUnitOfTheDateAndGoesOnAfterACursor() {
        String root = root("Root");
        unit("外科", root, 1, "げか");
        unit("内科", root, 1, "ないか");
        unit("眼科", root, 1, "がんか");
        unit("Zebra", root, 1, null);
        unit("Alpha", root, 1, null);
        unit("総務", root, 0, "そうむ");
        String later = service.create(named("Later", root, "2026-05-01")).unit().id();
        OrgUnitService.Search on = OrgUnitService.Search.all("2026-04-01");

        List<OrgUnitState> every = service.list(on, Page.of(null, null), null);
        assertEquals(List.of("総務", "Root", "眼科", "外科", "内科", "Alpha", "Zebra"), names(every));
        assertEquals(root, every.get(2).parentId());
        assertEquals(7, service.count(on));

        // Pages of two that each go on after the last unit of the page before.
        List<OrgUnitState> paged = new ArrayList<>();
        String after = null;
        List<OrgUnitState> page;
        do {
            page = service.list(on, Page.of(null, 2), after);
            paged.addAll(page);
            after = page.isEmpty() ? null : Cursor.encode(Cursor.ORG_UNITS, page.getLast().unit().id());
        } while (page.size() == 2);
        assertEquals(names(every), names(paged));

        assertEquals("invalid.cursor", assertThrows(FuhitoException.class,
                () -> service.list(on, Page.of(null, 2), Cursor.encode(Cursor.PEOPLE, root))).code());
        FuhitoException gone = assertThrows(FuhitoException.class,
                () -> service.list(on, Page.of(null, 2), Cursor.encode(Cursor.ORG_UNITS, later)));
        assertEquals("invalid.cursor_unit", gone.code());
        assertEquals(Map.of("field", "after", "id", later, "date", "2026-04-01"), gone.params());
    }

    @Test
    void theFiltersOfTheListGoTogether() {
        store.refItems = refItems;
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", ItemNames.of("Company"), 0, Map.of(),
                true, true));
        store.legalEntityTypes.add("type-company");
        refItems.insert(new RefItem("type-committee", RefKind.UNIT_TYPE, "committee", ItemNames.of("committee"), 0,
                Map.of(), true, false, false, true));
        String company = unitOfType("Company", "company", null);
        unitOfType("Sales", null, company);
        unitOfType("Safety", "committee", company);
        unitOfType("Union", "committee", null);
        unitOfType("Branch", "company", company);
        Page all = Page.of(null, null);

        assertEquals(List.of("Branch", "Company", "Safety", "Sales", "Union"),
                names(service.list(OrgUnitService.Search.all(null), all, null)));
        assertEquals(List.of("Branch", "Company"), names(service.list(legalEntities(null), all, null)));
        assertEquals(List.of("Safety", "Sales", "Union"), names(service.list(
                new OrgUnitService.Search(null, null, null, null, null, false, false, null, null), all, null)));
        assertEquals(List.of("Union"), names(service.list(
                new OrgUnitService.Search(null, null, null, null, null, false, true, null, null), all, null)));
        assertEquals(List.of("Company"), names(service.list(
                new OrgUnitService.Search(null, null, null, null, null, true, true, null, null), all, null)));
        assertEquals(List.of("Safety"), names(service.list(
                new OrgUnitService.Search("a", null, "committee", true, null, null, false, null, null), all, null)));
        assertEquals(1, service.count(new OrgUnitService.Search(null, null, null, false, null, null, true, null, null)));
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

        assertEquals(List.of("A", "Ending", "Moving", "B"), names(Trees.all(service, "2027-03-31", null)));
        assertEquals(List.of("A", "B", "Moved", "New"), names(Trees.all(service, "2027-04-01", null)));
        assertEquals(List.of(), names(Trees.all(service, "2026-03-31", null)));
        assertEquals(List.of("B", "Moved"), names(service.path(moving, "2027-04-01")));
        assertEquals(4, Trees.all(service, null, null).size());
    }

    @Test
    void treeReadsRejectAUnitThatIsNotThere() {
        String a = root("A");

        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.children("nope", "2026-04-01", null, null, Page.of(null, null))).code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.subtree("nope", "2026-04-01", null, null, Page.of(null, null))).code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.path("nope", "2026-04-01")).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.children(a, "2026-03-31", null, null, Page.of(null, null))).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.subtree(a, "2026-03-31", null, null, Page.of(null, null))).code());
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.path(a, "2026-03-31"));
        assertEquals("org_unit.not_valid_on", e.code());
        assertEquals(Map.of("id", a, "date", "2026-03-31"), e.params());
        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> Trees.all(service, "x", null)).code());
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
        assertEquals(List.of("Project", "Team"), names(Trees.all(service, on, "matrix")));
        assertEquals(List.of("Project"), names(service.list(roots(on, "matrix", null), all, null)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"), names(Trees.all(service, on, null)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"),
                names(Trees.all(service, on, "default")));

        assertEquals(List.of("Team"), names(service.children(project, on, "matrix", null, all)));
        assertEquals(List.of(), names(service.children(project, on, null, null, all)));
        assertEquals(List.of("Project", "Team"), names(service.subtree(project, on, "matrix", null, all)));
        assertEquals(List.of("Team"), names(service.subtree(team, on, "matrix", null, Page.of(0, 5))));
        assertEquals(List.of("Project", "Team"), names(service.path(team, on, "matrix")));
        assertEquals(List.of("Line", "Team"), names(service.path(team, on)));
        assertEquals(project, service.get(team, on, "matrix").parentId());
        assertEquals(line, service.get(team, on).parentId());
        assertNull(service.get(squad, on, "matrix").parentId());

        assertEquals("hierarchy.unknown",
                assertThrows(FuhitoException.class, () -> Trees.all(service, on, "nope")).code());
        assertEquals("hierarchy.unknown",
                assertThrows(FuhitoException.class, () -> service.path(team, on, "nope")).code());
    }

    @Test
    void anOrgUnitIsALegalEntityWhenItsUnitTypeSaysSo() {
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", ItemNames.of("Company"), 0, Map.of(),
                true, true));
        store.legalEntityTypes.add("type-company");
        unitType("department", true);
        String holding = service.create(new NewOrgUnit(null, "ja", ja("Holding"), "company", null,
                "2026-04-01", 2, null, null, null)).unit().id();
        String subsidiary = service.create(new NewOrgUnit(null, "ja", ja("Subsidiary"), "company",
                holding, "2026-04-01", 1, null, null, null)).unit().id();
        String sales = service.create(new NewOrgUnit(null, "ja", ja("Sales"), "department", holding,
                "2026-04-01", null, null, null, null)).unit().id();
        root("Untyped");

        assertTrue(service.get(holding, "2026-04-01").legalEntity());
        assertFalse(service.get(sales, "2026-04-01").legalEntity());
        // Untyped, then Holding with Sales and Subsidiary below it.
        assertEquals(List.of(false, true, false, true), Trees.all(service, "2026-04-01", null)
                .stream().map(OrgUnitState::legalEntity).toList());
        assertEquals(List.of(true), service.history(holding).versions().stream().map(OrgUnitState::legalEntity).toList());

        List<OrgUnitState> entities = service.list(legalEntities("2026-04-01"), Page.of(null, null), null);
        assertEquals(List.of("Subsidiary", "Holding"), names(entities));
        assertEquals(holding, entities.getFirst().parentId());
        assertEquals(List.of("Holding"), names(service.list(legalEntities("2026-04-01"), Page.of(1, 1), null)));
        assertEquals(List.of(), service.list(legalEntities("2026-03-31"), Page.of(null, null), null));

        // The type of a unit is dated, so a unit becomes a legal entity on a date.
        service.changeDetails(sales, new OrgUnitChange("2027-04-01", null, "ja", ja("Sales Inc."), "company",
                null, null));
        assertFalse(service.get(sales, "2027-03-31").legalEntity());
        assertTrue(service.get(sales, "2027-04-01").legalEntity());
        assertEquals(subsidiary, service.list(legalEntities("2027-04-01"), Page.of(null, null), null).get(1).unit().id());
        assertEquals(3, service.list(legalEntities("2027-04-01"), Page.of(null, null), null).size());
    }

    @Test
    void anEmployerStaysALegalEntityAndDoesNotEndWhileEmploymentsNameIt() {
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", ItemNames.of("Company"), 0, Map.of(),
                true, true));
        unitType("department", true);
        MemoryEmploymentStore employments = new MemoryEmploymentStore();
        store.employments = employments;
        String company = service.create(new NewOrgUnit(null, "ja", ja("Company"), "company", null,
                "2020-04-01", null, null, null, null)).unit().id();
        employments.insert(new Employment("e1", "alice", null, Employer.legalEntity(company),
                new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2028, 4, 1)), null, null, null, null, 1));
        OrgUnitChange toDepartment = new OrgUnitChange("2026-01-01", null, "ja", ja("Company"), "department",
                null, null);

        // The employment starts after the change, so the company would not be a legal entity on its first date.
        FuhitoException changed = assertThrows(FuhitoException.class, () -> service.changeDetails(company, toDepartment));
        assertEquals(FuhitoException.Reason.CONFLICT, changed.reason());
        assertEquals("org_unit.is_employer", changed.code());
        assertEquals("1", changed.params().get("count"));
        assertEquals("org_unit.is_employer", assertThrows(FuhitoException.class, () -> service.changeDetails(company,
                new OrgUnitChange("2026-01-01", null, "ja", ja("Company"), null, null, null))).code());
        // A change that keeps the type of a legal entity works, and so does a change after the first date.
        service.changeDetails(company, new OrgUnitChange("2026-01-01", null, "ja", ja("Company Inc."),
                "company", null, null));
        service.changeDetails(company, new OrgUnitChange("2027-01-01", null, "ja", ja("Company"),
                "department", null, null));

        FuhitoException ended = assertThrows(FuhitoException.class, () -> service.end(company, "2028-03-31"));
        assertEquals(FuhitoException.Reason.CONFLICT, ended.reason());
        assertEquals("org_unit.has_employments", ended.code());
        assertEquals("1", ended.params().get("count"));
        // The company can end on the day after the last day of the employment.
        assertEquals("Company", service.end(company, "2028-04-01").version().names().primary().name());
    }

    @Test
    void readsTheHistoryOfAUnit() {
        unitType("ward", true);
        String a = root("A");
        String b = root("B");
        String id = service.create(new NewOrgUnit("w", "ja", ja("Ward"), "ward", a, "2026-04-01", null,
                null, "X-1", "hr")).unit().id();
        service.changeDetails(id, rename("2026-10-01", "Ward 2"));
        service.move(id, null, "2027-04-01");
        service.move(id, b, "2028-04-01");

        OrgUnitHistory history = service.history(id);

        assertEquals(Arrays.asList("w", null), history.versions().stream().map(v -> v.version().code()).toList());
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
        OrgUnitState created = service.create(new NewOrgUnit(null, "ja", ja("Ward"), "ward", parentId,
                "2026-05-01", null, null, null, null));

        assertEquals(created, service.get(created.unit().id(), "2026-05-01"));
        assertEquals(created, service.get(created.unit().id(), "2030-01-01"));
    }

    @Test
    void todayFollowsTheClock() {
        String startsToday = service.create(named("A", null, "2026-06-15")).unit().id();
        String startsTomorrow = service.create(named("B", null, "2026-06-16")).unit().id();

        assertEquals("A", service.get(startsToday, null).version().names().primary().name());
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
        store.insertUnit(new OrgUnit("u1", null, null));
        store.versions.add(new OrgUnitVersion("v1", "u1", ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), null,
                Localized.of("ja", new OrgUnitName("U", null, null)), "gone", null, null, 0, Map.of()));

        assertNull(service.get("u1", "2026-04-01").unitType());
    }

    private void place(RefKind kind, String code, boolean active) {
        refItems.insert(new RefItem(kind + "-" + code, kind, code, ItemNames.of(code), 0, Map.of(), active));
    }

    private String placed(String name, String parentId, String workLocation, String costCenter) {
        return service.create(new NewOrgUnit(null, "ja", ja(name), null, workLocation, costCenter,
                parentId, "2026-04-01", null, null, null, null)).unit().id();
    }

    private static String places(OrgUnitState state) {
        LocationAndCost places = state.places();
        return places.workLocation() + " " + places.costCenter() + " " + places.effectiveWorkLocation() + " "
                + places.effectiveCostCenter();
    }

    @Test
    void aUnitTakesEachOfItsWorkLocationAndCostCenterFromTheNearestUnitAboveThatHasOne() {
        place(RefKind.WORK_LOCATION, "tokyo", true);
        place(RefKind.COST_CENTER, "cc-hq", true);
        place(RefKind.COST_CENTER, "cc-sales", true);
        String company = placed("Company", null, "tokyo", "cc-hq");
        String sales = placed("Sales", company, null, "cc-sales");
        String team = placed("Team", sales, null, null);
        String other = placed("Other", null, null, null);
        Page all = Page.of(null, null);

        assertEquals("tokyo cc-hq tokyo cc-hq", places(service.get(company, null)));
        assertEquals("null cc-sales tokyo cc-sales", places(service.get(sales, null)));
        assertEquals("null null tokyo cc-sales", places(service.get(team, null)));
        assertEquals("null null null null", places(service.get(other, null)));
        assertEquals(List.of("tokyo cc-hq tokyo cc-hq", "null cc-sales tokyo cc-sales", "null null tokyo cc-sales"),
                service.subtree(company, null, null, null, all).stream().map(OrgUnitServiceTest::places).toList());
        assertEquals(List.of("tokyo cc-hq tokyo cc-hq", "null cc-sales tokyo cc-sales", "null null tokyo cc-sales"),
                service.path(team, null, null).stream().map(OrgUnitServiceTest::places).toList());
        assertEquals(List.of(), service.children(team, null, null, null, all));
        assertEquals("WORK_LOCATION-tokyo", store.versions(company).getFirst().workLocationId());
    }

    @Test
    void aChangeOfTheDetailsOfAUnitAboveChangesWhatAppliesFromItsDate() {
        place(RefKind.WORK_LOCATION, "tokyo", true);
        place(RefKind.WORK_LOCATION, "osaka", true);
        String company = placed("Company", null, "tokyo", null);
        String team = placed("Team", company, null, null);

        service.changeDetails(company, new OrgUnitChange("2027-04-01", null, "ja", ja("Company"),
                null, "osaka", null, null, null));
        String moved = placed("Moved", null, null, null);
        service.move(team, moved, "2027-10-01");

        assertEquals("null null tokyo null", places(service.get(team, "2027-03-31")));
        assertEquals("null null osaka null", places(service.get(team, "2027-04-01")));
        assertEquals("null null null null", places(service.get(team, "2027-10-01")));
        // A history has the values of each version, and not the ones that apply.
        assertEquals(List.of("tokyo null null null", "osaka null null null"),
                service.history(company).versions().stream().map(OrgUnitServiceTest::places).toList());
    }

    @Test
    void aUnitNamesOnlyActiveItemsOfTheRightKindButKeepsOneThatWasDeactivated() {
        place(RefKind.WORK_LOCATION, "tokyo", true);
        place(RefKind.WORK_LOCATION, "closed", true);
        place(RefKind.COST_CENTER, "cc-old", false);
        place(RefKind.JOB_TITLE, "chief", true);
        String unit = placed("Unit", null, "closed", null);
        refItems.insert(new RefItem("WORK_LOCATION-closed", RefKind.WORK_LOCATION, "closed", ItemNames.of("closed"), 0,
                Map.of(), false));

        FuhitoException wrongKind = failure(new NewOrgUnit(null, "ja", ja("A"), null, "chief", null,
                null, "2026-04-01", null, null, null, null));
        assertEquals("ref_item.unknown", wrongKind.code());
        assertEquals(Map.of("kind", "WORK_LOCATION", "code", "chief"), wrongKind.params());
        assertEquals(Map.of("kind", "COST_CENTER", "code", "cc-old"), failure(new NewOrgUnit(null, "ja", ja("A"), null, null, "cc-old", null, "2026-04-01", null, null, null, null)).params());
        assertEquals("closed null closed null", places(service.changeDetails(unit, new OrgUnitChange("2026-05-01",
                null, "ja", ja("Unit"), null, "closed", null, null, null))));
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.changeDetails(placed(
                "B", null, "tokyo", null), new OrgUnitChange("2026-05-01", null, "ja", ja("B"), null,
                "closed", null, null, null))).code());
    }
}

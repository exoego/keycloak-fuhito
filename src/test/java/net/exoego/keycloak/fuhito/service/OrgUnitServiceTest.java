package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUnitServiceTest {

    // 2026-06-15 in Tokyo. It is still 2026-06-14 in UTC.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-14T16:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final OrgUnitService service = new OrgUnitService(store, refItems, CLOCK);

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

        OrgUnitLink link = store.links.get(0);
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

package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A division with a ward below it, and an HR contact of the division who is no member of it.
 */
class UnitRoleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryUnitRoleStore store = new MemoryUnitRoleStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final EmploymentService employments = new EmploymentService(employmentStore, assignmentStore, refItems,
            MemoryPersonStore.linked(Set.of("alice", "bob")), new MemoryReportingLineStore(), store,
            new AnyEmployer());
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK);
    private final UnitRoleService service = new UnitRoleService(store,
            new UnitRoleService.Sources(employmentStore, orgUnitStore, refItems), Today.of(CLOCK));

    private String division;
    private String ward;
    private String alice;
    private String bob;

    @BeforeEach
    void createTheDivision() {
        store.orgUnits = orgUnitStore;
        store.refItems = refItems;
        orgUnitStore.unitRoles = store;
        for (String code : List.of("hr-contact", "approver")) {
            refItems.insert(new RefItem(code, RefKind.UNIT_ROLE, code, code, Map.of(), 0, Map.of(), true));
        }
        division = unit("Division", null);
        ward = unit("Ward", division);
        alice = employ("alice");
        bob = employ("bob");
    }

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    private String employ(String userId) {
        return employments.start(new NewEmployment(userId, "2026-04-01", null, null, null, null, null, null, null,
                null, null)).employment().id();
    }

    private static List<String> summary(List<UnitRoleService.View> views) {
        return views.stream().map(view -> view.role().userId() + " " + view.code()).toList();
    }

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void aRoleOverADivisionCoversTheWardsBelowOnlyWhenItSaysSo() {
        UnitRoleService.View hr = service.add(alice, division, "hr-contact", true, "2026-04-01", null);
        service.add(bob, division, "approver", null, "2026-04-01", null);
        service.add(bob, ward, "hr-contact", null, "2026-05-01", null);

        assertEquals("hr-contact", hr.code());
        assertTrue(hr.role().includesDescendants());
        assertEquals("alice", hr.role().userId());
        assertEquals(hr, service.get(hr.role().id()));
        assertEquals(List.of("bob hr-contact", "alice hr-contact"), summary(service.holders(ward, null, null,
                Page.of(null, null))));
        assertEquals(List.of("alice hr-contact"), summary(service.holders(ward, null, "2026-04-15",
                Page.of(null, null))));
        assertEquals(List.of("bob approver", "alice hr-contact"), summary(service.holders(division, null, null,
                Page.of(null, null))));
        assertEquals(List.of("bob approver"), summary(service.holders(division, "approver", null,
                Page.of(null, null))));
        assertEquals(List.of("bob approver", "bob hr-contact"), summary(service.ofPerson("bob", null,
                Page.of(null, null))));
    }

    @Test
    void rejectsARoleThatDoesNotFit() {
        assertEquals("unit_role.employment_unknown", code(() -> service.add("nope", division, "approver", null,
                "2026-04-01", null)));
        assertEquals("unit_role.outside_employment", code(() -> service.add(alice, division, "approver", null,
                "2026-03-01", null)));
        assertEquals("unit_role.org_unit_unknown", code(() -> service.add(alice, "nope", "approver", null,
                "2026-04-01", null)));
        assertEquals("ref_item.unknown", code(() -> service.add(alice, division, "nurse", null, "2026-04-01",
                null)));
        assertEquals("invalid.period", code(() -> service.add(alice, division, "approver", null, "2026-04-01",
                "2026-04-01")));
        service.add(alice, division, "approver", null, "2026-04-01", "2026-10-01");
        assertEquals("unit_role.overlap", code(() -> service.add(alice, division, "approver", null, "2026-09-01",
                null)));
        service.add(alice, division, "approver", null, "2026-10-01", null);
        assertEquals("unit_role.not_found", code(() -> service.get("nope")));
        assertEquals("org_unit.not_found", code(() -> service.holders("nope", null, null, Page.of(null, null))));
    }

    @Test
    void endsAndDeletesARole() {
        String id = service.add(alice, ward, "approver", null, "2026-04-01", null).role().id();

        assertEquals("unit_role.end_outside", code(() -> service.end(id, "2026-04-01")));
        assertEquals("2026-09-01", service.end(id, "2026-09-01").role().period().to().toString());
        assertEquals("org_unit.has_unit_roles", code(() -> orgUnits.end(ward, "2026-08-01")));
        service.delete(id);
        assertEquals("unit_role.not_found", code(() -> service.get(id)));
    }

    @Test
    void theEndOfTheEmploymentEndsTheRoleAndATransferCarriesIt() {
        service.add(alice, division, "approver", null, "2026-04-01", null);
        service.add(alice, ward, "approver", null, "2027-01-01", null);
        service.add(bob, division, "approver", null, "2026-04-01", null);

        employments.end(alice, "2026-10-01", null);
        assertEquals(List.of("2026-10-01"), store.findByEmployment(alice).stream()
                .map(role -> role.period().to().toString()).toList());

        EmploymentService.Transfer moved = employments.transfer(bob, new TransferRequest("other", "2026-09-01", null,
                null));
        String next = moved.started().employment().id();
        assertEquals("2026-09-01", store.findByEmployment(bob).getFirst().period().to().toString());
        assertEquals(List.of("2026-09-01"), store.findByEmployment(next).stream()
                .map(role -> role.period().from().toString()).toList());
        assertFalse(store.findByEmployment(next).getFirst().includesDescendants());
    }
}

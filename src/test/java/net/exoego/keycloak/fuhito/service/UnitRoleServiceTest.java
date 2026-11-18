package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryUnitRoleStore store = new MemoryUnitRoleStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final EmploymentService employments = new EmploymentService(employmentStore, externalIds, assignmentStore, refItems,
            MemoryPersonStore.linked(Set.of("alice", "bob")), new MemoryReportingLineStore(), store,
            new AnyEmployer());
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, CLOCK);
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
        assertEquals(List.of("bob hr-contact", "alice hr-contact"), summary(service.holders(ward, null, false, null,
                Page.of(null, null))));
        assertEquals(List.of("alice hr-contact"), summary(service.holders(ward, null, false, "2026-04-15",
                Page.of(null, null))));
        assertEquals(List.of("bob approver", "alice hr-contact"), summary(service.holders(division, null, false, null,
                Page.of(null, null))));
        assertEquals(List.of("bob approver"), summary(service.holders(division, "approver", false, null,
                Page.of(null, null))));
        // The HR contact of the ward hides the one of the division when only the nearest are asked for.
        assertEquals(List.of("bob hr-contact"), summary(service.holders(ward, null, true, null,
                Page.of(null, null))));
        assertEquals(List.of("alice hr-contact"), summary(service.holders(ward, null, true, "2026-04-15",
                Page.of(null, null))));
        assertEquals(List.of("bob approver", "bob hr-contact"), summary(service.ofPerson("bob", null,
                Page.of(null, null))));
    }

    private static List<String> ids(List<UnitRoleService.Holder> holders) {
        return holders.stream().map(holder -> holder.view().role().id()).toList();
    }

    private static List<String> sorted(String... ids) {
        return Stream.of(ids).sorted().toList();
    }

    @Test
    void listsTheRolesOfADateAcrossTheRealmByIdWithTheirNames() {
        refItems.insert(new RefItem("safety", RefKind.UNIT_ROLE, "safety-officer", "Safety officer", Map.of(), 0,
                Map.of(), true));
        String contact = service.add(alice, division, "hr-contact", true, "2026-04-01", null).role().id();
        String officer = service.add(bob, ward, "safety-officer", null, "2026-04-01", null).role().id();
        String approver = service.add(bob, division, "approver", null, "2026-07-01", null).role().id();
        UnitRoleService.Search today = new UnitRoleService.Search(null, null, null);
        UnitRoleService.Search july = new UnitRoleService.Search(null, null, "2026-07-01");

        List<UnitRoleService.Holder> listed = service.list(today, Page.of(null, null), null);
        assertEquals(sorted(contact, officer), ids(listed));
        UnitRoleService.Holder safety = listed.get(ids(listed).indexOf(officer));
        assertEquals("safety-officer", safety.view().code());
        assertEquals("Safety officer", safety.roleName());
        assertEquals("Ward", safety.orgUnitName());
        assertEquals("bob", safety.view().role().userId());
        assertEquals(2, service.count(today));
        assertEquals(sorted(contact, officer, approver), ids(service.list(july, Page.of(null, null), null)));
        service.end(officer, "2026-09-01");
        assertEquals(sorted(contact, approver), ids(service.list(new UnitRoleService.Search(null, null,
                "2026-09-01"), Page.of(null, null), null)));

        UnitRoleService.Search approvers = new UnitRoleService.Search("approver", null, "2026-07-01");
        assertEquals(List.of(approver), ids(service.list(approvers, Page.of(null, null), null)));
        assertEquals(1, service.count(approvers));
        // The HR contact of the division covers the ward, but it is over the division.
        assertEquals(List.of(officer), ids(service.list(new UnitRoleService.Search(null, ward, "2026-07-01"),
                Page.of(null, null), null)));
        assertEquals(sorted(contact, approver), ids(service.list(
                new UnitRoleService.Search(null, division, "2026-07-01"), Page.of(null, null), null)));
        assertEquals(0, service.count(new UnitRoleService.Search("approver", ward, "2026-07-01")));

        List<String> pages = new ArrayList<>();
        String after = null;
        do {
            List<UnitRoleService.Holder> page = service.list(july, Page.of(null, 2), after);
            pages.addAll(ids(page));
            after = page.size() < 2 ? null : Cursor.encode(Cursor.UNIT_ROLES, page.getLast().view().role().id());
        } while (after != null);
        assertEquals(sorted(contact, officer, approver), pages);
        assertEquals(List.of(pages.get(1)), ids(service.list(july, Page.of(1, 1), null)));
    }

    @Test
    void theNameOfARoleIsTheNameOfTheDate() {
        String role = service.add(alice, division, "approver", null, "2026-04-01", null).role().id();
        RefItem approver = refItems.findByCode(RefKind.UNIT_ROLE, "approver").orElseThrow();
        new RefItemService(refItems).update(approver.id(), "Head approver", null, null, null, null,
                RefItemService.Marks.NONE, "2026-07-01");

        assertEquals(List.of("approver"), service.list(new UnitRoleService.Search(null, null, "2026-06-30"),
                Page.of(null, null), null).stream().filter(h -> h.view().role().id().equals(role))
                .map(UnitRoleService.Holder::roleName).toList());
        assertEquals(List.of("Head approver"), service.list(new UnitRoleService.Search(null, null, "2026-07-01"),
                Page.of(null, null), null).stream().filter(h -> h.view().role().id().equals(role))
                .map(UnitRoleService.Holder::roleName).toList());
    }

    @Test
    void rejectsAListWithAnUnknownRoleABadDateOrACursorOfAnotherList() {
        assertEquals("ref_item.unknown", code(() -> service.list(new UnitRoleService.Search("nurse", null, null),
                Page.of(null, null), null)));
        assertEquals("ref_item.unknown", code(() -> service.count(new UnitRoleService.Search("nurse", null, null))));
        assertEquals("invalid.date", code(() -> service.list(new UnitRoleService.Search(null, null, "soon"),
                Page.of(null, null), null)));
        assertEquals("invalid.cursor", code(() -> service.list(new UnitRoleService.Search(null, null, null),
                Page.of(null, null), Cursor.encode(Cursor.PEOPLE, alice))));
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
        String later = orgUnits.create(new NewOrgUnit(null, "Later", null, null, null, null, null, null, "2026-07-01",
                null, null, null, null)).unit().id();
        assertEquals("unit_role.org_unit_not_valid", code(() -> service.add(alice, later, "approver", null,
                "2026-04-01", null)));
        assertEquals("ref_item.unknown", code(() -> service.holders(ward, "nurse", false, null, Page.of(null, null))));
        assertEquals("unit_role.not_found", code(() -> service.get("nope")));
        assertEquals("org_unit.not_found", code(() -> service.holders("nope", null, false, null, Page.of(null, null))));
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
        // The transfer returns the new role, so that the change log can name it.
        assertEquals(List.of(store.findByEmployment(next).getFirst().id()),
                moved.unitRoles().stream().map(view -> view.role().id()).toList());
        assertEquals("approver", moved.unitRoles().getFirst().code());
    }
}

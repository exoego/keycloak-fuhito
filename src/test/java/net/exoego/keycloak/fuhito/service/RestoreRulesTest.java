package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreRulesTest {

    private static RealmExport.Unit unit(String id, RealmExport.Parent... parents) {
        return new RealmExport.Unit(id, 1L, null, null, null, List.of(new RealmExport.Version("v-" + id,
                "2020-04-01", null, null, "ja", Map.of(), null, null, null, 0, Map.of())), List.of(parents),
                List.of(), null);
    }

    private static RealmExport.Parent parent(String id, String parentId, String from, String to) {
        return new RealmExport.Parent(id, "default", parentId, from, to);
    }

    private static RealmExport units(RealmExport.Unit... units) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, List.of(units), null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void aCycleAboveALinkFailsAtALinkOfTheCycle() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreRules.require(units(
                unit("a", parent("l-a", "b", "2020-04-01", null)),
                unit("b", parent("l-b", "c", "2020-04-01", null)),
                unit("c", parent("l-c", "b", "2020-04-01", null)))));

        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", "org-unit-parents", "id", "l-b", "field", "parentId", "rule", "org_unit.cycle"),
                e.params());
    }

    @Test
    void twoLinksWithNoSharedDateMakeNoCycle() {
        assertDoesNotThrow(() -> RestoreRules.require(units(
                unit("a", parent("l-a", "b", "2020-04-01", "2021-04-01")),
                unit("b", parent("l-b", "a", "2021-04-01", null)))));
    }

    private static RealmExport.EmploymentRow employment(String id, String personId, String employerId,
            String externalEmployerId) {
        return new RealmExport.EmploymentRow(id, 1L, personId, employerId, externalEmployerId, "2020-04-01", null,
                "S-1", null, null, null, List.of(new RealmExport.Terms("t-" + id, "2020-04-01", null, null, null,
                        "ACTIVE", null, null, null, Map.of())));
    }

    private static RealmExport.AssignmentRows assignment(String id) {
        return new RealmExport.AssignmentRows(id, 1L, List.of(new RealmExport.Period("r-" + id, "e-1", "u-1",
                "2020-04-01", null, null, null, null, null, null, false, false, null, 0, Map.of(), null)));
    }

    private static RealmExport.Line line(String id, String assignmentId, String kind) {
        return new RealmExport.Line(id, 1L, assignmentId, "p-2", kind, "2020-04-01", null);
    }

    // u-1 is a legal entity.
    private static RealmExport document(List<RealmExport.EmploymentRow> employments,
            List<RealmExport.AssignmentRows> assignments, List<RealmExport.Line> lines) {
        RealmExport.Unit employer = new RealmExport.Unit("u-1", 1L, null, null, null, List.of(new RealmExport.Version(
                "v-1", "2020-04-01", null, null, "ja", Map.of(), "corp", null, null, 0, Map.of())), List.of(),
                List.of(), null);
        RealmExport.Item corp = new RealmExport.Item("corp", 1L, "UNIT_TYPE", "corp", 0, Map.of(), true, true, false,
                false, false, List.of(new RealmExport.ItemNames("n-1", null, null, "ja", Map.of())));
        return new RealmExport(RealmExport.FORMAT, null, List.of(corp), null, null, List.of(employer), null,
                employments, assignments, lines, null, null, null, null, null);
    }

    @Test
    void twoEmployersMayGiveOneStaffNumberToTwoPeople() {
        assertDoesNotThrow(() -> RestoreRules.require(document(List.of(employment("e-1", "p-1", "u-1", null),
                employment("e-2", "p-2", null, "agency"), employment("e-3", "p-2", null, "agency")), null, null)));
    }

    private static RealmExport orders(String announcedAt, String announcedBy) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(new RealmExport.Order("o-1", 1L, null, null, "April", null, null, "2024-04-01", null,
                        null, null, "2024-03-01T00:00:00Z", announcedAt, announcedBy)), null);
    }

    // An export gives no username for a user of another realm.
    @Test
    void aChangeOrderNamesWhoAnnouncedItOnlyWithTheTime() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreRules.require(orders(null, "nurse")));

        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", "change-orders", "id", "o-1", "field", "announcedAt"), e.params());
        assertDoesNotThrow(() -> RestoreRules.require(orders("2024-03-02T00:00:00Z", "nurse")));
        assertDoesNotThrow(() -> RestoreRules.require(orders("2024-03-02T00:00:00Z", null)));
    }

    @Test
    void aSolidLineMayShareDatesWithADottedLineOrALineOfAnotherAssignment() {
        assertDoesNotThrow(() -> RestoreRules.require(document(List.of(employment("e-1", "p-1", "u-1", null)),
                List.of(assignment("a-1"), assignment("a-2")),
                List.of(line("rl-1", "a-1", "SOLID"), line("rl-2", "a-1", "DOTTED"), line("rl-3", "a-2", "SOLID")))));
    }
}

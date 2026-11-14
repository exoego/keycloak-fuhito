package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentServiceTest {

    private final MemoryEmploymentStore store = new MemoryEmploymentStore();
    private final MemoryAssignmentStore assignments = new MemoryAssignmentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final EmploymentService service =
            new EmploymentService(store, assignments, refItems,
            MemoryPersonStore.linked(Set.of("alice", "bob")),
            new MemoryReportingLineStore());

    private static NewEmployment minimal(String userId, String validFrom) {
        return new NewEmployment(userId, validFrom, null, null, null, null, null, null, null, null, null);
    }

    private RefItem item(RefKind kind, String code, boolean active) {
        RefItem item = new RefItem(kind + "-" + code, kind, code, code, Map.of(), 0, Map.of(), active);
        refItems.insert(item);
        return item;
    }

    private FuhitoException failure(NewEmployment request) {
        return assertThrows(FuhitoException.class, () -> service.start(request));
    }

    @Test
    void startsAnEmploymentWithOnlyAUserAndADate() {
        EmploymentView view = service.start(minimal("alice", "2026-04-01"));

        assertEquals(36, view.employment().id().length());
        assertEquals("alice", view.employment().userId());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), view.employment().period());
        assertNull(view.employment().staffNumber());
        assertNull(view.employment().endReasonId());
        assertNull(view.endReason());
        assertEquals(1, view.terms().size());
        EmploymentTerms terms = view.terms().getFirst().terms();
        assertEquals(view.employment().id(), terms.employmentId());
        assertEquals(view.employment().period(), terms.period());
        assertEquals(EmploymentStatus.ACTIVE, terms.status());
        assertNull(terms.employmentTypeId());
        assertNull(terms.gradeId());
        assertNull(terms.workPercent());
        assertEquals(Map.of(), terms.attributes());
        assertNull(view.terms().getFirst().employmentType());
        assertNull(view.terms().getFirst().grade());
        assertEquals(view, service.get(view.employment().id()));
    }

    @Test
    void startsAFixedTermEmploymentWithEveryValue() {
        RefItem temporary = item(RefKind.EMPLOYMENT_TYPE, "temporary", true);
        RefItem grade = item(RefKind.GRADE, "g3", true);

        EmploymentView view = service.start(new NewEmployment("alice", "2026-04-01", "2027-04-01", "000123",
                "temporary", "g3", "ON_LEAVE", new BigDecimal("60.5"), Map.of("union", true), "E-1", "hr"));

        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), view.employment().period());
        assertEquals("000123", view.employment().staffNumber());
        assertEquals("E-1", view.employment().externalId());
        assertEquals("hr", view.employment().source());
        EmploymentView.TermsView terms = view.terms().getFirst();
        assertEquals(temporary.id(), terms.terms().employmentTypeId());
        assertEquals("temporary", terms.employmentType());
        assertEquals(grade.id(), terms.terms().gradeId());
        assertEquals("g3", terms.grade());
        assertEquals(EmploymentStatus.ON_LEAVE, terms.terms().status());
        assertEquals(new BigDecimal("60.5"), terms.terms().workPercent());
        assertEquals(Map.of("union", true), terms.terms().attributes());
        assertEquals(view.employment().period(), terms.terms().period());
    }

    @Test
    void aUserCanBeRehiredAndCanHoldTwoContractsAtOnce() {
        service.start(new NewEmployment("alice", "2020-04-01", "2024-04-01", "000123", null, null, null, null, null,
                null, null));

        // A rehire keeps the staff number. A second contract overlaps the first.
        service.start(new NewEmployment("alice", "2026-04-01", null, "000123", null, null, null, null, null, null,
                null));
        service.start(minimal("alice", "2026-10-01"));

        assertEquals(3, store.employments.size());
    }

    @Test
    void rejectsAStaffNumberOfAnotherUser() {
        service.start(new NewEmployment("alice", "2020-04-01", "2024-04-01", "000123", null, null, null, null, null,
                null, null));

        FuhitoException e = failure(new NewEmployment("bob", "2026-04-01", null, "000123", null, null, null, null,
                null, null, null));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("employment.staff_number_taken", e.code());
        assertEquals(Map.of("staffNumber", "000123"), e.params());
    }

    @Test
    void rejectsAnUnknownPerson() {
        FuhitoException e = failure(minimal("mallory", "2026-04-01"));

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("employment.person_unknown", e.code());
        assertEquals(Map.of("personId", "mallory"), e.params());
    }

    @Test
    void rejectsUnknownOrInactiveReferenceItems() {
        item(RefKind.EMPLOYMENT_TYPE, "retired-type", false);
        item(RefKind.GRADE, "full-time", true);

        for (String type : new String[] {"nope", "retired-type", "full-time"}) {
            FuhitoException e = failure(new NewEmployment("alice", "2026-04-01", null, null, type, null, null, null,
                    null, null, null));
            assertEquals("ref_item.unknown", e.code());
            assertEquals(Map.of("kind", "EMPLOYMENT_TYPE", "code", type), e.params());
        }
        assertEquals(Map.of("kind", "GRADE", "code", "nope"), failure(new NewEmployment("alice", "2026-04-01", null,
                null, null, "nope", null, null, null, null, null)).params());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.start(null)).code());
        assertEquals("invalid.required", failure(minimal(null, "2026-04-01")).code());
        assertEquals("invalid.required", failure(minimal("alice", null)).code());
        assertEquals("invalid.date", failure(new NewEmployment("alice", "2026-04-01", "soon", null, null, null, null,
                null, null, null, null)).code());
        assertEquals("invalid.period", failure(new NewEmployment("alice", "2026-04-01", "2026-04-01", null, null,
                null, null, null, null, null, null)).code());
        assertEquals("invalid.code", failure(new NewEmployment("alice", "2026-04-01", null, "no spaces", null, null,
                null, null, null, null, null)).code());
        assertEquals("invalid.choice", failure(new NewEmployment("alice", "2026-04-01", null, null, null, null,
                "RETIRED", null, null, null, null)).code());
        assertEquals("invalid.percent", failure(new NewEmployment("alice", "2026-04-01", null, null, null, null,
                null, new BigDecimal("120"), null, null, null)).code());
        assertTrue(store.employments.isEmpty());
        assertTrue(store.terms.isEmpty());
    }

    private static TermsChange status(String validFrom, String status) {
        return new TermsChange(validFrom, null, null, status, null, null);
    }

    private static List<String> statuses(EmploymentView view) {
        return view.terms().stream()
                .map(t -> t.terms().period().from() + " " + t.terms().period().to() + " " + t.terms().status())
                .toList();
    }

    @Test
    void aLeaveAndAReturnSplitTheTerms() {
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();

        service.changeTerms(id, status("2026-10-01", "ON_LEAVE"));
        EmploymentView view = service.changeTerms(id, status("2027-04-01", "ACTIVE"));

        assertEquals(List.of(
                "2026-04-01 2026-10-01 ACTIVE",
                "2026-10-01 2027-04-01 ON_LEAVE",
                "2027-04-01 null ACTIVE"), statuses(view));
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), view.employment().period());
    }

    @Test
    void aChangeInsideOlderTermsKeepsTheLaterOnes() {
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();
        service.changeTerms(id, status("2027-04-01", "SUSPENDED"));

        EmploymentView view = service.changeTerms(id, status("2026-10-01", "ON_LEAVE"));

        assertEquals(List.of(
                "2026-04-01 2026-10-01 ACTIVE",
                "2026-10-01 2027-04-01 ON_LEAVE",
                "2027-04-01 null SUSPENDED"), statuses(view));
    }

    @Test
    void aChangeOnTheFirstDateOfTermsCorrectsThem() {
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();
        String termsId = store.terms(id).getFirst().id();

        EmploymentView view = service.changeTerms(id, status("2026-04-01", "ON_LEAVE"));

        assertEquals(List.of("2026-04-01 null ON_LEAVE"), statuses(view));
        assertEquals(termsId, view.terms().getFirst().terms().id());
    }

    @Test
    void aChangeReplacesAllTerms() {
        item(RefKind.EMPLOYMENT_TYPE, "part-time", true);
        RefItem fullTime = item(RefKind.EMPLOYMENT_TYPE, "full-time", true);
        RefItem grade = item(RefKind.GRADE, "g4", true);
        String id = service.start(new NewEmployment("alice", "2026-04-01", null, null, "part-time", null, null,
                new BigDecimal("50"), Map.of("note", "x"), null, null)).employment().id();

        EmploymentView view = service.changeTerms(id, new TermsChange("2026-10-01", "full-time", "g4", null,
                new BigDecimal("100"), Map.of("union", true)));

        EmploymentView.TermsView changed = view.terms().get(1);
        assertEquals(fullTime.id(), changed.terms().employmentTypeId());
        assertEquals("full-time", changed.employmentType());
        assertEquals(grade.id(), changed.terms().gradeId());
        assertEquals(EmploymentStatus.ACTIVE, changed.terms().status());
        assertEquals(new BigDecimal("100"), changed.terms().workPercent());
        assertEquals(Map.of("union", true), changed.terms().attributes());
        assertEquals("part-time", view.terms().getFirst().employmentType());

        EmploymentView.TermsView cleared = service.changeTerms(id, status("2027-04-01", null)).terms().get(2);
        assertNull(cleared.terms().employmentTypeId());
        assertNull(cleared.terms().gradeId());
        assertNull(cleared.terms().workPercent());
        assertEquals(Map.of(), cleared.terms().attributes());
    }

    @Test
    void termsOfAFixedTermEmploymentEndWithIt() {
        String id = service.start(new NewEmployment("alice", "2026-04-01", "2027-04-01", null, null, null, null,
                null, null, null, null)).employment().id();

        EmploymentView view = service.changeTerms(id, status("2026-10-01", "ON_LEAVE"));

        assertEquals(List.of("2026-04-01 2026-10-01 ACTIVE", "2026-10-01 2027-04-01 ON_LEAVE"), statuses(view));
        for (String outside : new String[] {"2026-03-31", "2027-04-01"}) {
            FuhitoException e = assertThrows(FuhitoException.class,
                    () -> service.changeTerms(id, status(outside, "ACTIVE")));
            assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
            assertEquals("employment.not_valid_on", e.code());
            assertEquals(Map.of("id", id, "date", outside), e.params());
        }
    }

    @Test
    void termsMayKeepADeactivatedItemButNotTakeANewOne() {
        RefItem old = item(RefKind.EMPLOYMENT_TYPE, "old-type", true);
        item(RefKind.GRADE, "retired-grade", false);
        String id = service.start(new NewEmployment("alice", "2026-04-01", null, null, "old-type", null, null, null,
                null, null, null)).employment().id();
        refItems.update(new RefItem(old.id(), RefKind.EMPLOYMENT_TYPE, "old-type", "old", Map.of(), 0, Map.of(),
                false));

        EmploymentView view = service.changeTerms(id, new TermsChange("2026-10-01", "old-type", null, "ON_LEAVE",
                null, null));
        assertEquals("old-type", view.terms().get(1).employmentType());

        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.changeTerms(id,
                new TermsChange("2027-04-01", null, "retired-grade", null, null, null))).code());
    }

    @Test
    void rejectsABadChangeOfTermsAndWritesNothing() {
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();

        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.changeTerms(id, null)).code());
        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.changeTerms(id, status(null, "ACTIVE"))).code());
        assertEquals("employment.not_found", assertThrows(FuhitoException.class,
                () -> service.changeTerms("nope", status("2026-10-01", "ACTIVE"))).code());
        assertEquals("invalid.choice", assertThrows(FuhitoException.class,
                () -> service.changeTerms(id, status("2026-10-01", "GONE"))).code());
        assertEquals("invalid.percent", assertThrows(FuhitoException.class, () -> service.changeTerms(id,
                new TermsChange("2026-10-01", null, null, null, new BigDecimal("0"), null))).code());
        assertEquals(List.of("2026-04-01 null ACTIVE"), statuses(service.get(id)));
    }

    private Assignment assignment(String rowId, String assignmentId, String employmentId, String from, String to) {
        Assignment row = new Assignment(rowId, assignmentId, employmentId, "unit", new ValidPeriod(
                LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), null, null, null, false, false, null,
                0, Map.of(), null, null);
        assignments.insert(row);
        return row;
    }

    private List<String> assignmentPeriods() {
        return assignments.rows.stream()
                .map(r -> r.rowId() + " " + r.period().from() + " " + r.period().to())
                .sorted()
                .toList();
    }

    @Test
    void leavingEndsTheEmploymentItsTermsAndItsAssignments() {
        RefItem resignation = item(RefKind.END_REASON, "resignation", true);
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();
        service.changeTerms(id, status("2026-10-01", "ON_LEAVE"));
        service.changeTerms(id, status("2028-04-01", "ACTIVE"));
        assignment("open", "a1", id, "2026-04-01", null);
        assignment("ended-before", "a2", id, "2026-04-01", "2026-10-01");
        assignment("ends-later", "a3", id, "2026-04-01", "2029-04-01");
        assignment("changed-1", "a4", id, "2026-04-01", "2027-10-01");
        assignment("changed-2", "a4", id, "2027-10-01", null);
        assignment("future", "a5", id, "2028-04-01", null);
        String other = service.start(minimal("bob", "2026-04-01")).employment().id();
        assignment("other", "a6", other, "2026-04-01", null);

        EmploymentView view = service.end(id, "2027-04-01", "resignation");

        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), view.employment().period());
        assertEquals(resignation.id(), view.employment().endReasonId());
        assertEquals("resignation", view.endReason());
        assertEquals(List.of("2026-04-01 2026-10-01 ACTIVE", "2026-10-01 2027-04-01 ON_LEAVE"), statuses(view));
        assertEquals(List.of(
                "changed-1 2026-04-01 2027-04-01",
                "ended-before 2026-04-01 2026-10-01",
                "ends-later 2026-04-01 2027-04-01",
                "open 2026-04-01 2027-04-01",
                "other 2026-04-01 null"), assignmentPeriods());
    }

    @Test
    void aLaterEndExtendsAFixedTermEmploymentAndItsLastTerms() {
        String id = service.start(new NewEmployment("alice", "2026-04-01", "2027-04-01", null, null, null, null,
                null, null, null, null)).employment().id();
        service.changeTerms(id, status("2026-10-01", "ON_LEAVE"));
        assignment("bound", "a1", id, "2026-04-01", "2027-04-01");

        EmploymentView view = service.end(id, "2028-04-01", null);

        assertEquals(LocalDate.of(2028, 4, 1), view.employment().period().to());
        assertNull(view.endReason());
        assertEquals(List.of("2026-04-01 2026-10-01 ACTIVE", "2026-10-01 2028-04-01 ON_LEAVE"), statuses(view));
        // Assignments are not extended.
        assertEquals(List.of("bound 2026-04-01 2027-04-01"), assignmentPeriods());
    }

    @Test
    void removingTheEndTakesBackAResignation() {
        item(RefKind.END_REASON, "resignation", true);
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();
        assignment("open", "a1", id, "2026-04-01", null);
        service.end(id, "2027-04-01", "resignation");

        EmploymentView view = service.removeEnd(id);

        assertNull(view.employment().period().to());
        assertNull(view.employment().endReasonId());
        assertEquals(List.of("2026-04-01 null ACTIVE"), statuses(view));
        assertEquals(List.of("open 2026-04-01 2027-04-01"), assignmentPeriods());
        assertEquals("employment.not_found",
                assertThrows(FuhitoException.class, () -> service.removeEnd("nope")).code());
    }

    @Test
    void aSecondEndMayKeepADeactivatedReason() {
        RefItem reason = item(RefKind.END_REASON, "old-reason", true);
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();
        service.end(id, "2027-04-01", "old-reason");
        refItems.update(new RefItem(reason.id(), RefKind.END_REASON, "old-reason", "old", Map.of(), 0, Map.of(),
                false));

        assertEquals("old-reason", service.end(id, "2027-03-01", "old-reason").endReason());
        assertNull(service.end(id, "2027-02-01", null).endReason());
    }

    @Test
    void rejectsABadEnd() {
        item(RefKind.END_REASON, "retired-reason", false);
        String id = service.start(minimal("alice", "2026-04-01")).employment().id();

        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.end(id, null, null)).code());
        assertEquals("employment.not_found",
                assertThrows(FuhitoException.class, () -> service.end("nope", "2027-04-01", null)).code());
        for (String date : new String[] {"2026-04-01", "2026-03-01"}) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> service.end(id, date, null));
            assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
            assertEquals("employment.not_valid_on", e.code());
        }
        for (String reason : new String[] {"nope", "retired-reason"}) {
            assertEquals("ref_item.unknown",
                    assertThrows(FuhitoException.class, () -> service.end(id, "2027-04-01", reason)).code());
        }
        assertNull(service.get(id).employment().period().to());
    }

    @Test
    void rejectsAnUnknownEmployment() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get("nope"));

        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("employment.not_found", e.code());
    }

    @Test
    void readsAnEmploymentWhoseReferenceItemWasRemoved() {
        RefItem type = item(RefKind.EMPLOYMENT_TYPE, "temporary", true);
        String id = service.start(new NewEmployment("alice", "2026-04-01", null, null, "temporary", null, null, null,
                null, null, null)).employment().id();
        refItems.delete(type.id());

        assertNull(service.get(id).terms().getFirst().employmentType());
    }
}

package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

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
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final EmploymentService service =
            new EmploymentService(store, refItems, Set.of("alice", "bob")::contains);

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
        EmploymentTerms terms = view.terms().get(0).terms();
        assertEquals(view.employment().id(), terms.employmentId());
        assertEquals(view.employment().period(), terms.period());
        assertEquals(EmploymentStatus.ACTIVE, terms.status());
        assertNull(terms.employmentTypeId());
        assertNull(terms.gradeId());
        assertNull(terms.workPercent());
        assertEquals(Map.of(), terms.attributes());
        assertNull(view.terms().get(0).employmentType());
        assertNull(view.terms().get(0).grade());
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
        EmploymentView.TermsView terms = view.terms().get(0);
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
    void rejectsAnUnknownUser() {
        FuhitoException e = failure(minimal("mallory", "2026-04-01"));

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("employment.user_unknown", e.code());
        assertEquals(Map.of("userId", "mallory"), e.params());
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

        assertNull(service.get(id).terms().get(0).employmentType());
    }
}

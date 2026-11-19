package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
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
    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final EmploymentService service =
            new EmploymentService(store, externalIds, assignments, refItems,
            MemoryPersonStore.linked(Set.of("alice", "bob")),
            lines, new MemoryUnitRoleStore(), new AnyEmployer());

    private static NewEmployment minimal(String userId, String validFrom) {
        return new NewEmployment(userId, validFrom, null, null, null, null, null, null, null, null, null);
    }

    private RefItem item(RefKind kind, String code, boolean active) {
        RefItem item = new RefItem(kind + "-" + code, kind, code, ItemNames.of(code), 0, Map.of(), active);
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
        assertEquals(Optional.of(view.employment().id()),
                externalIds.idOf(ExternalId.Kind.EMPLOYMENT, "hr", "E-1"));
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
    void listsEmploymentsByStaffNumberEmployerAndDate() {
        String a = service.start(new NewEmployment("alice", "2020-04-01", "2025-04-01", "1001", null, null, null,
                null, null, null, null, null, "company-a")).employment().id();
        String b = service.start(new NewEmployment("bob", "2024-04-01", null, "1002", null, null, null, null, null,
                null, null, null, "company-a")).employment().id();
        String c = service.start(new NewEmployment("bob", "2024-04-01", null, "1001", null, null, null, null, null,
                null, null, null, "company-b")).employment().id();
        Page all = Page.of(null, null);

        assertEquals(List.of(a, c).stream().sorted().toList(), service.list(new EmploymentService.Search("1001", null,
                null), all, null).stream().map(view -> view.employment().id()).toList());
        assertEquals(List.of(b), service.list(new EmploymentService.Search(null, "company-a", "2025-06-01"), all,
                null).stream().map(view -> view.employment().id()).toList());
        assertEquals(2, service.count(new EmploymentService.Search(null, null, "2025-06-01")));
        EmploymentView first = service.list(new EmploymentService.Search(null, null, null), Page.of(null, 1), null)
                .getFirst();
        assertEquals(1, first.terms().size());
        assertEquals(2, service.list(new EmploymentService.Search(null, null, null), all,
                Cursor.encode(Cursor.EMPLOYMENTS, first.employment().id())).size());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.count(new EmploymentService.Search(null, null, "soon"))).code());
        item(RefKind.GRADE, "g9", true);
        service.changeTerms(b, new TermsChange("2025-04-01", null, "g9", null, null, null));
        assertEquals(List.of("g9"), service.list(new EmploymentService.Search(null, "company-a", "2025-06-01"), all,
                null).getFirst().terms().stream().map(EmploymentView.TermsView::grade).filter(g -> g != null)
                .toList());
    }

    private String fixedTerm(String personId, String validFrom, String contractEnd) {
        return service.start(new NewEmployment(personId, validFrom, null, null, null, null, null, null, null,
                contractEnd, null, null, null, null)).employment().id();
    }

    private static TermsChange contract(String validFrom, String contractEnd) {
        return new TermsChange(validFrom, null, null, null, null, null, contractEnd, null);
    }

    @Test
    void aFixedTermContractHasAnEndThatARenewalMovesWithoutEndingTheEmployment() {
        String id = fixedTerm("alice", "2025-04-01", "2026-03-31");
        assertEquals(LocalDate.of(2026, 3, 31), service.get(id).terms().getFirst().terms().contractEndDate());

        EmploymentView renewed = service.changeTerms(id, contract("2026-04-01", "2027-03-31"));

        assertEquals(List.of(LocalDate.of(2026, 3, 31), LocalDate.of(2027, 3, 31)),
                renewed.terms().stream().map(t -> t.terms().contractEndDate()).toList());
        assertNull(renewed.employment().period().to());
        // A conversion to an indefinite contract is terms with no end.
        EmploymentView converted = service.changeTerms(id, new TermsChange("2030-04-01", null, null, null, null,
                null));
        assertNull(converted.terms().getLast().terms().contractEndDate());
    }

    @Test
    void aContractEndIsNotBeforeTheStartOfItsTerms() {
        String id = service.start(minimal("alice", "2025-04-01")).employment().id();

        // A contract can end on its first day.
        service.changeTerms(id, contract("2025-04-01", "2025-04-01"));
        FuhitoException early = assertThrows(FuhitoException.class,
                () -> service.changeTerms(id, contract("2025-05-01", "2025-04-30")));
        assertEquals("employment.contract_end_before_terms", early.code());
        assertEquals(Map.of("contractEndDate", "2025-04-30", "validFrom", "2025-05-01"), early.params());
        assertEquals("invalid.date", failure(new NewEmployment("bob", "2025-04-01", null, null, null, null, null,
                null, null, "soon", null, null, null, null)).code());
        assertEquals(1, service.get(id).terms().size());
    }

    @Test
    void listsTheEmploymentsWhoseContractEndsInARange() {
        String a = fixedTerm("alice", "2025-04-01", "2026-03-31");
        String b = fixedTerm("bob", "2025-04-01", "2026-04-30");
        service.start(minimal("bob", "2025-04-01"));
        service.changeTerms(a, contract("2026-04-01", "2027-03-31"));
        LocalDate today = LocalDate.of(2026, 3, 1);
        Page all = Page.of(null, null);

        assertEquals(List.of(a), ids(new EmploymentService.Search(null, null, null, "2026-03-01", "2026-04-01",
                today), all));
        assertEquals(List.of(b), ids(new EmploymentService.Search(null, null, null, "2026-04-01", null, today),
                all));
        assertEquals(List.of(a), ids(new EmploymentService.Search(null, null, null, null, "2026-04-01", today),
                all));
        // The terms of asOf decide. The renewal of the first one ends in 2027, and the second one has passed.
        assertEquals(List.of(a), ids(new EmploymentService.Search(null, null, "2026-06-01", "2027-01-01", null,
                today), all));
        assertEquals(2, service.count(new EmploymentService.Search(null, null, null, "2026-01-01", "2027-01-01",
                today)));
        assertEquals("invalid.period", assertThrows(FuhitoException.class,
                () -> service.count(new EmploymentService.Search(null, null, null, "2026-04-01", "2026-04-01",
                        today))).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.count(new EmploymentService.Search(null, null, null, null, "later", today))).code());
    }

    private List<String> ids(EmploymentService.Search search, Page page) {
        return service.list(search, page, null).stream().map(view -> view.employment().id()).toList();
    }

    @Test
    void anEmploymentKeepsItsEmployerAndEachEmployerGivesItsOwnStaffNumbers() {
        EmploymentView atA = service.start(new NewEmployment("alice", "2020-04-01", null, "1001", null, null, null, null,
                null, null, null, null, "company-a"));
        EmploymentView unnamed = service.start(minimal("bob", "2010-04-01"));

        assertEquals("company-a", atA.employment().employerId());
        assertEquals(AnyEmployer.DEFAULT, unnamed.employment().employerId());
        assertEquals("company-a", service.end(atA.employment().id(), "2030-04-01", null).employment().employerId());

        // Another company may give the same number to another person. The same company may not.
        EmploymentView atB = service.start(new NewEmployment("bob", "2020-04-01", null, "1001", null, null, null, null,
                null, null, null, null, "company-b"));
        assertEquals("company-b", atB.employment().employerId());
        assertEquals("employment.staff_number_taken", failure(new NewEmployment("bob", "2021-04-01", null, "1001",
                null, null, null, null, null, null, null, null, "company-a")).code());
        // The same person keeps the number in a second employment at the same company.
        service.start(new NewEmployment("alice", "2031-04-01", null, "1001", null, null, null, null, null, null, null,
                null, "company-a"));
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
        refItems.update(new RefItem(old.id(), RefKind.EMPLOYMENT_TYPE, "old-type", ItemNames.of("old"), 0, Map.of(),
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

    @Test
    void aTransferEndsOneEmploymentAndStartsTheNextWithWhatHoldsOnTheDate() {
        item(RefKind.EMPLOYMENT_TYPE, "full-time", true);
        item(RefKind.END_REASON, "transfer", true);
        String old = service.start(new NewEmployment("alice", "2020-04-01", "2030-04-01", "Y-1001", "full-time", null,
                null, null, new BigDecimal("80"), "2030-03-31", Map.of("desk", "3F"), "HR-1", "hr", "company-y"))
                .employment().id();
        service.changeTerms(old, status("2029-04-01", "ON_LEAVE"));
        assignment("ward-row", "ward", old, "2020-04-01", null);
        assignment("past-row", "past", old, "2020-04-01", "2025-04-01");
        assignment("future-row", "future", old, "2028-04-01", null);
        lines.insert(new ReportingLine("solid", "ward", "bob", "bob", ReportingLine.Kind.SOLID,
                new ValidPeriod(LocalDate.of(2020, 4, 1), LocalDate.of(2029, 4, 1)), 1));
        lines.insert(new ReportingLine("old-line", "ward", "bob", "bob", ReportingLine.Kind.DOTTED,
                new ValidPeriod(LocalDate.of(2020, 4, 1), LocalDate.of(2021, 4, 1)), 1));

        EmploymentService.Transfer transfer = service.transfer(old,
                new TransferRequest("company-x", "2027-04-01", "X-2040", "transfer"));

        Employment ended = transfer.ended().employment();
        assertEquals(LocalDate.of(2027, 4, 1), ended.period().to());
        assertEquals("company-y", ended.employerId());
        assertEquals("transfer", transfer.ended().endReason());
        Employment started = transfer.started().employment();
        assertEquals("alice", started.personId());
        assertEquals("company-x", started.employerId());
        assertEquals("X-2040", started.staffNumber());
        assertEquals(old, started.continuesEmploymentId());
        assertNull(started.source());
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.EMPLOYMENT, started.id()));
        // The new employment lasts as long as the old one would have. It keeps the leave that was stored for 2029.
        assertEquals(new ValidPeriod(LocalDate.of(2027, 4, 1), LocalDate.of(2030, 4, 1)), started.period());
        assertEquals(List.of("2027-04-01 2029-04-01 ACTIVE", "2029-04-01 2030-04-01 ON_LEAVE"),
                statuses(transfer.started()));
        assertEquals("full-time", transfer.started().terms().getFirst().employmentType());
        EmploymentTerms terms = store.terms(started.id()).getFirst();
        assertEquals(new BigDecimal("80"), terms.workPercent());
        assertEquals(LocalDate.of(2030, 3, 31), terms.contractEndDate());
        assertEquals(Map.of("desk", "3F"), terms.attributes());

        // The assignment of the date and the one for 2028 move over. The one that ended in 2025 stays.
        assertEquals(2, transfer.assignmentIds().size());
        Assignment moved = assignments.rows(transfer.assignmentIds().get(0)).getFirst();
        assertEquals(started.id(), moved.employmentId());
        assertEquals("unit", moved.orgUnitId());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2027, 4, 1)), moved.period());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2028, 4, 1)),
                assignments.rows(transfer.assignmentIds().get(1)).getFirst().period());
        assertEquals(LocalDate.of(2027, 4, 1), assignments.rows("ward").getFirst().period().to());
        assertEquals(List.of(), assignments.rows("future"));
        assertEquals(1, assignments.rows("past").size());

        // The line of the date moves over with its own end. The line that ended in 2021 stays.
        assertEquals(1, transfer.reportingLines().size());
        ReportingLine line = transfer.reportingLines().getFirst();
        assertEquals(transfer.assignmentIds().get(0), line.assignmentId());
        assertEquals("bob", line.managerPersonId());
        assertEquals(ReportingLine.Kind.SOLID, line.kind());
        assertEquals(new ValidPeriod(LocalDate.of(2027, 4, 1), LocalDate.of(2029, 4, 1)), line.period());
        assertEquals(LocalDate.of(2027, 4, 1), lines.find("solid").orElseThrow().period().to());
    }

    @Test
    void aMergerMovesEveryPersonOfOneEmployerToAnother() {
        String alice = service.start(new NewEmployment("alice", "2020-04-01", null, "1001", null, null, null, null, null,
                null, null, null, "company-y")).employment().id();
        String bob = service.start(new NewEmployment("bob", "2021-04-01", null, "1002", null, null, null, null, null,
                null, null, null, "company-y")).employment().id();
        // These stay. One left before the date, one starts on the date, and one has another employer.
        String left = service.start(new NewEmployment("alice", "2010-04-01", "2015-04-01", "0900", null, null, null, null,
                null, null, null, null, "company-y")).employment().id();
        String starting = service.start(new NewEmployment("bob", "2027-04-01", null, "1003", null, null, null, null, null,
                null, null, null, "company-y")).employment().id();
        String elsewhere = service.start(new NewEmployment("bob", "2020-04-01", null, "5000", null, null, null, null,
                null, null, null, null, "company-z")).employment().id();

        List<EmploymentService.Transfer> transfers = service.transferAll(new TransferAllRequest("company-y",
                "company-x", "2027-04-01", null, true, Map.of(bob, "X-2")));

        assertEquals(List.of(alice, bob), transfers.stream().map(t -> t.ended().employment().id()).toList());
        assertEquals(List.of("company-x", "company-x"),
                transfers.stream().map(t -> t.started().employment().employerId()).toList());
        // Alice keeps her number, and Bob gets the one that the request names.
        assertEquals(List.of("1001", "X-2"),
                transfers.stream().map(t -> t.started().employment().staffNumber()).toList());
        for (String untouched : List.of(left, starting, elsewhere)) {
            assertEquals(store.employments.get(untouched).period(), service.get(untouched).employment().period());
        }
        assertNull(store.employments.get(starting).period().to());
        assertEquals(7, store.employments.size());

        // Without the wish to keep them, the people get no staff number.
        String carol = service.start(new NewEmployment("alice", "2028-04-01", null, "7001", null, null, null, null, null,
                null, null, null, "company-z")).employment().id();
        assertNull(service.transferAll(new TransferAllRequest("company-z", "company-w", "2029-04-01", null, false,
                null)).stream().filter(t -> t.ended().employment().id().equals(carol)).findFirst().orElseThrow()
                .started().employment().staffNumber());
    }

    @Test
    void aMergerIsRejectedAsAWholeWhenOnePartCannotHappen() {
        String alice = service.start(new NewEmployment("alice", "2020-04-01", null, "1001", null, null, null, null, null,
                null, null, null, "company-y")).employment().id();
        service.start(new NewEmployment("bob", "2020-04-01", null, "1001", null, null, null, null, null, null, null, null,
                "company-x"));

        assertEquals("employment.staff_number_taken", assertThrows(FuhitoException.class,
                () -> service.transferAll(new TransferAllRequest("company-y", "company-x", "2027-04-01", null, true,
                        null))).code());
        assertEquals("employment.not_transferred", assertThrows(FuhitoException.class,
                () -> service.transferAll(new TransferAllRequest("company-y", "company-x", "2027-04-01", null, false,
                        Map.of("nope", "1")))).code());
        // An earlier part of the same merger takes the number first.
        String bob = service.start(new NewEmployment("bob", "2021-04-01", null, "1002", null, null, null, null, null,
                null, null, null, "company-y")).employment().id();
        assertEquals("employment.staff_number_taken", assertThrows(FuhitoException.class,
                () -> service.transferAll(new TransferAllRequest("company-y", "company-w", "2027-04-01", null, false,
                        Map.of(alice, "W-1", bob, "W-1")))).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.transferAll(new TransferAllRequest(null, "company-x", "2027-04-01", null, false,
                        null))).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.transferAll(new TransferAllRequest("company-y", null, "2027-04-01", null, false,
                        null))).code());
        // Nobody of an employer with no employment moves, and that is no error.
        assertEquals(List.of(), service.transferAll(new TransferAllRequest("company-q", "company-x", "2027-04-01",
                null, false, null)));
        assertNull(store.employments.get(alice).period().to());
    }

    @Test
    void aMergerMayGiveOnePersonTheSameNumberTwice() {
        for (String from : List.of("2020-04-01", "2021-04-01")) {
            service.start(new NewEmployment("alice", from, null, "1001", null, null, null, null, null, null, null,
                    null, "company-y"));
        }

        assertEquals(List.of("1001", "1001"), service.transferAll(new TransferAllRequest("company-y", "company-x",
                "2027-04-01", null, true, null)).stream().map(t -> t.started().employment().staffNumber()).toList());
    }

    @Test
    void aTransferIsRejectedWhenItCannotHappen() {
        String old = service.start(new NewEmployment("alice", "2020-04-01", "2030-04-01", "1001", null, null, null, null,
                null, null, null, null, "company-y")).employment().id();
        service.start(new NewEmployment("bob", "2020-04-01", null, "2040", null, null, null, null, null, null, null, null,
                "company-x"));

        assertEquals("employment.same_employer", assertThrows(FuhitoException.class,
                () -> service.transfer(old, new TransferRequest("company-y", "2027-04-01", null, null))).code());
        assertEquals("employment.staff_number_taken", assertThrows(FuhitoException.class,
                () -> service.transfer(old, new TransferRequest("company-x", "2027-04-01", "2040", null))).code());
        // The person must be employed on the day before and on the date.
        for (String date : List.of("2020-04-01", "2019-04-01", "2030-04-01")) {
            assertEquals("employment.not_valid_on", assertThrows(FuhitoException.class,
                    () -> service.transfer(old, new TransferRequest("company-x", date, null, null))).code(), date);
        }
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.transfer(old, new TransferRequest(null, "2027-04-01", null, null))).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.transfer(old, new TransferRequest("company-x", "soon", null, null))).code());
        assertEquals("employment.not_found", assertThrows(FuhitoException.class,
                () -> service.transfer("nope", new TransferRequest("company-x", "2027-04-01", null, null))).code());
        assertEquals(2, store.employments.size());
        assertEquals(LocalDate.of(2030, 4, 1), store.employments.get(old).period().to());
    }

    @Test
    void aMoveThatIsPlannedAfterATransferStaysAMoveBetweenTheCopies() {
        String old = service.start(new NewEmployment("alice", "2020-04-01", null, null, null, null, null, null,
                null, null, null, null, "company-y")).employment().id();
        assignment("first-row", "first", old, "2020-04-01", "2027-07-01");
        // The move to the ward is planned for July, and the transfer comes in April.
        assignments.insert(new Assignment("moved-row", "moved", old, "ward", ValidPeriod.startingAt(
                LocalDate.of(2027, 7, 1)), null, null, null, "home", "cc", false, false, null, 0, Map.of(), "hr",
                "first", 1));
        externalIds.insert(new ExternalId("x-moved", ExternalId.Kind.ASSIGNMENT, "moved", "hr", "M-1", 1));

        EmploymentService.Transfer transfer = service.transfer(old, new TransferRequest("company-z", "2027-04-01",
                null, null));

        String first = transfer.assignmentIds().get(0);
        Assignment moved = assignments.rows(transfer.assignmentIds().get(1)).getFirst();
        assertEquals("first", assignments.rows(first).getFirst().continuesAssignmentId());
        assertEquals(first, moved.continuesAssignmentId());
        // The old assignment of July is gone, so its copy takes its IDs of other systems.
        assertEquals(List.of(), assignments.rows("moved"));
        assertEquals(Optional.of(moved.assignmentId()), externalIds.idOf(ExternalId.Kind.ASSIGNMENT, "hr", "M-1"));
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.ASSIGNMENT, "moved"));
        // A transfer keeps the work location and the cost center.
        assertEquals("home", moved.workLocationId());
        assertEquals("cc", moved.costCenterId());
        assertEquals("hr", moved.source());
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.ASSIGNMENT, first));
    }

    private Assignment assignment(String rowId, String assignmentId, String employmentId, String from, String to) {
        Assignment row = new Assignment(rowId, assignmentId, employmentId, "unit", new ValidPeriod(
                LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), null, null, null, null, null, false,
                false, null, 0, Map.of(), null, null, 1);
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
        refItems.update(new RefItem(reason.id(), RefKind.END_REASON, "old-reason", ItemNames.of("old"), 0, Map.of(),
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

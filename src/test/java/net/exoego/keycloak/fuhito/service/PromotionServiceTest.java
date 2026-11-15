package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.EmploymentView.TermsView;
import net.exoego.keycloak.fuhito.service.PromotionService.Promotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final EmploymentService employments = new EmploymentService(employmentStore, assignmentStore, refItems,
            MemoryPersonStore.linked(Set.of("nurse")), new MemoryReportingLineStore(), new AnyEmployer());
    private final AssignmentService assignments = new AssignmentService(assignmentStore, employmentStore,
            orgUnitStore, refItems, new MemoryReportingLineStore());
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK);
    private final PromotionService service = new PromotionService(employments, assignments, assignmentStore);

    private String employment;
    private String ward;
    private String committee;

    @BeforeEach
    void employTheNurse() {
        assignmentStore.orgUnits = orgUnitStore;
        assignmentStore.employments = employmentStore;
        for (String[] item : new String[][] {{"OCCUPATION", "nurse"}, {"JOB_TITLE", "staff"}, {"JOB_TITLE", "chief"},
                {"EMPLOYMENT_TYPE", "part-time"}, {"GRADE", "g1"}, {"GRADE", "g2"}, {"ASSIGNMENT_KIND", "regular"}}) {
            refItems.insert(new RefItem(item[0] + "-" + item[1], RefKind.valueOf(item[0]), item[1], item[1], Map.of(),
                    0, Map.of(), true));
        }
        String ward = orgUnits.create(new NewOrgUnit(null, "Ward", null, null, null, null, null, null, "2026-04-01",
                null, null, null, null)).unit().id();
        String committee = orgUnits.create(new NewOrgUnit(null, "Committee", null, null, null, null, null, null,
                "2026-04-01", null, null, null, null)).unit().id();
        employment = employments.start(new NewEmployment("nurse", "2026-04-01", null, "N-1", "part-time", "g1",
                "ON_LEAVE", new BigDecimal("50"), Map.of("shift", "night"), null, null)).employment().id();
        this.ward = assignments.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", "nurse",
                "regular", false, true, null, new BigDecimal("80"), 3, Map.of("desk", "A"), null, null))
                .assignmentId();
        this.committee = assignments.add(new AssignmentValues(employment, committee, "2026-04-01", null, null, null,
                null, false, false, null, null, null, null, null, null)).assignmentId();
    }

    private static PromotionRequest request(String validFrom, String grade, String jobTitle, String assignmentId,
            Boolean head) {
        return new PromotionRequest(validFrom, grade, jobTitle, assignmentId, head);
    }

    private FuhitoException failure(String employmentId, PromotionRequest request) {
        return assertThrows(FuhitoException.class, () -> service.promote(employmentId, request));
    }

    @Test
    void aPromotionSetsTheGradeAndTheJobTitleAndKeepsEverythingElse() {
        Promotion promotion = service.promote(employment, request("2026-10-01", "g2", "chief", null, true));

        List<TermsView> terms = promotion.employment().terms();
        assertEquals(List.of("g1", "g2"), terms.stream().map(TermsView::grade).toList());
        assertEquals("2026-10-01", terms.get(1).terms().period().from().toString());
        assertEquals("part-time", terms.get(1).employmentType());
        assertEquals(EmploymentStatus.ON_LEAVE, terms.get(1).terms().status());
        assertEquals(new BigDecimal("50"), terms.get(1).terms().workPercent());
        assertEquals(Map.of("shift", "night"), terms.get(1).terms().attributes());

        // With no assignment in the request, the primary one gets the job title.
        assertEquals(ward, promotion.assignment().assignmentId());
        List<AssignmentView.Row> rows = promotion.assignment().rows();
        assertEquals(List.of("staff", "chief"), rows.stream().map(AssignmentView.Row::jobTitle).toList());
        Assignment after = rows.get(1).row();
        assertEquals("2026-10-01", after.period().from().toString());
        assertTrue(after.head());
        assertTrue(after.primary());
        assertEquals("nurse", rows.get(1).occupation());
        assertEquals("regular", rows.get(1).kind());
        assertEquals(new BigDecimal("80"), after.allocationPercent());
        assertEquals(3, after.displayOrder());
        assertEquals(Map.of("desk", "A"), after.attributes());
        assertFalse(rows.get(0).row().head());
    }

    @Test
    void aPromotionCanChangeOnlyOnePart() {
        Promotion onlyGrade = service.promote(employment, request("2026-10-01", "g2", null, null, null));
        assertNull(onlyGrade.assignment());
        assertEquals(1, assignments.get(ward).rows().size());

        // The request can name another assignment than the primary one. The job title stays without one.
        Promotion onlyHead = service.promote(employment, request("2026-11-01", null, null, committee, true));
        assertEquals(committee, onlyHead.assignment().assignmentId());
        assertTrue(onlyHead.assignment().rows().getLast().row().head());
        assertNull(onlyHead.assignment().rows().getLast().jobTitle());
        assertEquals(2, onlyHead.employment().terms().size());

        // The head flag stays when the request has only a job title.
        Promotion onlyTitle = service.promote(employment, request("2026-12-01", null, "chief", committee, null));
        assertTrue(onlyTitle.assignment().rows().getLast().row().head());
        assertEquals("chief", onlyTitle.assignment().rows().getLast().jobTitle());
    }

    @Test
    void aBadPromotionWritesNothing() {
        assertEquals("employment.promotion_empty", failure(employment, request("2026-10-01", null, null, null, null))
                .code());
        assertEquals("invalid.required", failure(employment, null).code());
        assertEquals("employment.not_found", failure("nope", request("2026-10-01", "g2", null, null, null)).code());
        assertEquals("employment.not_valid_on",
                failure(employment, request("2026-03-01", "g2", null, null, null)).code());
        // The grade is good and the assignment is not. Nothing is written.
        FuhitoException other = failure(employment, request("2026-10-01", "g2", "chief", "nope", null));
        assertEquals("employment.assignment_not_on", other.code());
        assertEquals("nope", other.params().get("assignmentId"));
        assertEquals(1, employments.get(employment).terms().size());
        assertEquals("ref_item.unknown", failure(employment, request("2026-10-01", "g9", null, null, null)).code());

        // With no primary assignment on the date, the request must name the assignment.
        assignments.end(ward, "2026-09-01");
        assertEquals("employment.no_primary_assignment",
                failure(employment, request("2026-10-01", null, "chief", null, null)).code());
        assertEquals("chief", service.promote(employment, request("2026-10-01", null, "chief", committee, null))
                .assignment().rows().getLast().jobTitle());
    }
}

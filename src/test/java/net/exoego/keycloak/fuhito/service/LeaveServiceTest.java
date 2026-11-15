package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.EmploymentView.TermsView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeaveServiceTest {

    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final EmploymentService employments = new EmploymentService(employmentStore, new MemoryAssignmentStore(),
            refItems, MemoryPersonStore.linked(Set.of("nurse")), new MemoryReportingLineStore(), new AnyEmployer());
    private final LeaveService service = new LeaveService(employments);

    private String employment;

    @BeforeEach
    void employTheNurse() {
        for (String[] item : new String[][] {{"EMPLOYMENT_TYPE", "part-time"}, {"GRADE", "g1"}, {"GRADE", "g2"}}) {
            refItems.insert(new RefItem(item[0] + "-" + item[1], RefKind.valueOf(item[0]), item[1], item[1], Map.of(),
                    0, Map.of(), true));
        }
        employment = employments.start(new NewEmployment("nurse", "2026-04-01", "2028-04-01", null, "part-time",
                "g1", "SUSPENDED", new BigDecimal("50"), Map.of("shift", "night"), null, null)).employment().id();
    }

    private static List<String> statuses(EmploymentView view) {
        return view.terms().stream()
                .map(terms -> terms.terms().period().from() + " " + terms.terms().status())
                .toList();
    }

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void aLeaveWithAnEndBringsBackTheStatusOfBefore() {
        EmploymentView view = service.start(employment, "2026-10-01", "2027-01-01");

        assertEquals(List.of("2026-04-01 SUSPENDED", "2026-10-01 ON_LEAVE", "2027-01-01 SUSPENDED"), statuses(view));
        // Every other value of the terms stays, during the leave and after it.
        for (TermsView terms : view.terms()) {
            assertEquals("part-time", terms.employmentType());
            assertEquals("g1", terms.grade());
            assertEquals(new BigDecimal("50"), terms.terms().workPercent());
            assertEquals(Map.of("shift", "night"), terms.terms().attributes());
        }
    }

    @Test
    void aLeaveWithNoEndLastsUntilAReturn() {
        assertEquals(List.of("2026-04-01 SUSPENDED", "2026-10-01 ON_LEAVE"),
                statuses(service.start(employment, "2026-10-01", null)));

        EmploymentView back = service.end(employment, "2027-02-01");

        assertEquals(List.of("2026-04-01 SUSPENDED", "2026-10-01 ON_LEAVE", "2027-02-01 ACTIVE"), statuses(back));
        assertEquals("g1", back.terms().getLast().grade());
        assertEquals(EmploymentStatus.ACTIVE, back.terms().getLast().terms().status());
    }

    @Test
    void anEarlyReturnKeepsTheReturnThatWasStored() {
        service.start(employment, "2026-10-01", "2027-01-01");

        assertEquals(List.of("2026-04-01 SUSPENDED", "2026-10-01 ON_LEAVE", "2026-12-01 ACTIVE",
                "2027-01-01 SUSPENDED"), statuses(service.end(employment, "2026-12-01")));
    }

    @Test
    void aLeaveCanLastToTheEndOfTheEmployment() {
        assertEquals(List.of("2026-04-01 SUSPENDED", "2026-10-01 ON_LEAVE"),
                statuses(service.start(employment, "2026-10-01", "2028-04-01")));
    }

    @Test
    void rejectsALeaveOrAReturnThatDoesNotFit() {
        assertEquals("employment.not_found", code(() -> service.start("nope", "2026-10-01", null)));
        assertEquals("employment.not_valid_on", code(() -> service.start(employment, "2026-03-01", null)));
        assertEquals("employment.not_valid_on", code(() -> service.start(employment, "2026-10-01", "2028-05-01")));
        assertEquals("invalid.period", code(() -> service.start(employment, "2026-10-01", "2026-10-01")));
        assertEquals("invalid.date", code(() -> service.start(employment, "soon", null)));
        assertEquals("employment.not_on_leave", code(() -> service.end(employment, "2026-10-01")));
        assertEquals("employment.not_valid_on", code(() -> service.end(employment, "2028-04-01")));
        assertEquals(1, employments.get(employment).terms().size());

        service.start(employment, "2026-10-01", "2027-01-01");
        assertEquals("employment.already_on_leave", code(() -> service.start(employment, "2026-11-01", null)));
        // The return of January is a change that is stored for later.
        FuhitoException stored = assertThrows(FuhitoException.class,
                () -> service.start(employment, "2026-06-01", null));
        assertEquals("employment.terms_change_stored", stored.code());
        assertEquals("2027-01-01", stored.params().get("date"));
        // After the return, a new leave is fine.
        assertEquals(5, service.start(employment, "2027-06-01", "2027-07-01").terms().size());
    }
}

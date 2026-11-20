package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RememberedEmployersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);
    private static final Employer E = Employer.legalEntity("E");

    private final List<String> asked = new ArrayList<>();
    private final RememberedEmployers employers = new RememberedEmployers(new Employers() {
        @Override
        public Employer of(String requestedId, String externalEmployer, LocalDate firstDate) {
            asked.add("of " + requestedId + " " + externalEmployer + " " + firstDate);
            if ("bad".equals(requestedId) || "bad".equals(externalEmployer)) {
                throw FuhitoException.invalid("employment.employer_unknown", Map.of("employerId", "bad"));
            }
            if (externalEmployer != null) {
                return Employer.external("item-" + externalEmployer);
            }
            return Employer.legalEntity(requestedId == null ? "only" : requestedId);
        }

        @Override
        public void requireCovers(Employer employer, ValidPeriod period) {
            asked.add("covers " + employer.legalEntityId() + " " + period.from() + " " + period.to());
            if (period.to() == null) {
                throw FuhitoException.conflict("employment.employer_ends", Map.of("employerId", "E"));
            }
        }
    });

    @Test
    void asksOnceForEachQuestionAndGivesTheSameAnswerOrError() {
        ValidPeriod open = ValidPeriod.startingAt(DAY);
        ValidPeriod year = new ValidPeriod(DAY, DAY.plusYears(1));

        for (int i = 0; i < 3; i++) {
            assertEquals(Employer.legalEntity("only"), employers.of(null, null, DAY));
            assertEquals(E, employers.of("E", null, DAY));
            assertEquals(Employer.external("item-agency"), employers.of(null, "agency", DAY));
            employers.requireCovers(E, year);
        }
        assertEquals(Employer.legalEntity("only"), employers.of(null, null, DAY.plusDays(1)));
        FuhitoException unknown = assertThrows(FuhitoException.class, () -> employers.of("bad", null, DAY));
        assertSame(unknown, assertThrows(FuhitoException.class, () -> employers.of("bad", null, DAY)));
        FuhitoException unknownCode = assertThrows(FuhitoException.class, () -> employers.of(null, "bad", DAY));
        assertSame(unknownCode, assertThrows(FuhitoException.class, () -> employers.of(null, "bad", DAY)));
        FuhitoException ends = assertThrows(FuhitoException.class, () -> employers.requireCovers(E, open));
        assertSame(ends, assertThrows(FuhitoException.class, () -> employers.requireCovers(E, open)));

        assertEquals(List.of("of null null 2026-04-01", "of E null 2026-04-01", "of null agency 2026-04-01",
                "covers E 2026-04-01 2027-04-01", "of null null 2026-04-02", "of bad null 2026-04-01",
                "of null bad 2026-04-01", "covers E 2026-04-01 null"), asked);
    }
}

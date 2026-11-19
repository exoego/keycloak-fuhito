package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RememberedEmployersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    private final List<String> asked = new ArrayList<>();
    private final RememberedEmployers employers = new RememberedEmployers(new Employers() {
        @Override
        public String of(String requestedId, LocalDate firstDate) {
            asked.add("of " + requestedId + " " + firstDate);
            if ("bad".equals(requestedId)) {
                throw FuhitoException.invalid("employment.employer_unknown", Map.of("employerId", requestedId));
            }
            return requestedId == null ? "only" : requestedId;
        }

        @Override
        public void requireCovers(String employerId, ValidPeriod period) {
            asked.add("covers " + employerId + " " + period.from() + " " + period.to());
            if (period.to() == null) {
                throw FuhitoException.conflict("employment.employer_ends", Map.of("employerId", employerId));
            }
        }
    });

    @Test
    void asksOnceForEachQuestionAndGivesTheSameAnswerOrError() {
        ValidPeriod open = ValidPeriod.startingAt(DAY);
        ValidPeriod year = new ValidPeriod(DAY, DAY.plusYears(1));

        for (int i = 0; i < 3; i++) {
            assertEquals("only", employers.of(null, DAY));
            assertEquals("E", employers.of("E", DAY));
            employers.requireCovers("E", year);
        }
        assertEquals("only", employers.of(null, DAY.plusDays(1)));
        FuhitoException unknown = assertThrows(FuhitoException.class, () -> employers.of("bad", DAY));
        assertSame(unknown, assertThrows(FuhitoException.class, () -> employers.of("bad", DAY)));
        FuhitoException ends = assertThrows(FuhitoException.class, () -> employers.requireCovers("E", open));
        assertSame(ends, assertThrows(FuhitoException.class, () -> employers.requireCovers("E", open)));

        assertEquals(List.of("of null 2026-04-01", "of E 2026-04-01", "covers E 2026-04-01 2027-04-01",
                "of null 2026-04-02", "of bad 2026-04-01", "covers E 2026-04-01 null"), asked);
    }
}

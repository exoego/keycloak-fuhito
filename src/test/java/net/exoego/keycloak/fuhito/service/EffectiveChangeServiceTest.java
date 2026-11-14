package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.EffectiveChange;
import net.exoego.keycloak.fuhito.domain.EffectiveChange.Kind;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffectiveChangeServiceTest {

    // 2026-04-01 in Tokyo, and still 2026-03-31 in UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final List<String> asked = new ArrayList<>();
    private final EffectiveChange found = new EffectiveChange(LocalDate.of(2026, 4, 1), Kind.ASSIGNMENT, "a1");
    private final EffectiveChangeService service = new EffectiveChangeService((from, to, first, max) -> {
        asked.add(from + " " + to + " " + first + " " + max);
        return List.of(found);
    }, CLOCK);

    @Test
    void asksForTodayOfTheRealmWhenTheRequestNamesNoDate() {
        assertEquals(List.of(found), service.list(null, null, Page.of(null, null)));

        assertEquals(List.of("2026-04-01 2026-04-02 0 100"), asked);
    }

    @Test
    void asksForTheRangeAndThePageOfTheRequest() {
        service.list("2026-04-01", "2026-05-01", Page.of(20, 10));
        service.list("2026-06-30", null, Page.of(null, null));

        assertEquals(List.of("2026-04-01 2026-05-01 20 10", "2026-06-30 2026-07-01 0 100"), asked);
    }

    @Test
    void rejectsABadDateAndAnEmptyRange() {
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.list("x", null, Page.of(null, null))).code());
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.list(null, "x", Page.of(null, null))).code());
        assertEquals("invalid.period", assertThrows(FuhitoException.class,
                () -> service.list("2026-04-01", "2026-04-01", Page.of(null, null))).code());
        assertEquals(List.of(), asked);
    }
}

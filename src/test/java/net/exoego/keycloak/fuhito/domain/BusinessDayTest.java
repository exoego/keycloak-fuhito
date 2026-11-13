package net.exoego.keycloak.fuhito.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessDayTest {

    private static LocalDate today(String utcInstant, String timeZone, String dayStart) {
        // The base clock is in another zone on purpose. Its zone must not matter.
        Clock base = Clock.fixed(Instant.parse(utcInstant), ZoneId.of("America/New_York"));
        return LocalDate.now(BusinessDay.clock(timeZone, dayStart, base));
    }

    @ParameterizedTest
    @CsvSource({
        // 08:00 on April 1 in Tokyo is still March 31 in UTC.
        "2026-03-31T23:00:00Z, Asia/Tokyo, , 2026-04-01",
        "2026-03-31T23:00:00Z, UTC, , 2026-03-31",
        "2026-03-31T14:59:59Z, Asia/Tokyo, , 2026-03-31",
        "2026-03-31T15:00:00Z, Asia/Tokyo, , 2026-04-01",
        "2026-03-31T15:00:00Z, Asia/Tokyo, 00:00, 2026-04-01",
        // With a day start of 04:00, the date changes at 04:00 in Tokyo, which is 19:00 UTC.
        "2026-03-31T15:00:00Z, Asia/Tokyo, 04:00, 2026-03-31",
        "2026-03-31T18:59:59Z, Asia/Tokyo, 04:00, 2026-03-31",
        "2026-03-31T19:00:00Z, Asia/Tokyo, 04:00, 2026-04-01",
        "2026-03-31T19:00:00Z, Asia/Tokyo, ' 04:00 ', 2026-04-01",
        "2026-04-01T14:58:00Z, Asia/Tokyo, 23:59, 2026-03-31",
        "2026-03-31T23:00:00Z, ' Asia/Tokyo ', '  ', 2026-04-01",
        "2026-03-31T23:00:00Z, +09:00, , 2026-04-01"
    })
    void theDateFollowsTheTimeZoneAndTheDayStartOfTheRealm(String instant, String timeZone, String dayStart,
            LocalDate expected) {
        assertEquals(expected, today(instant, timeZone, dayStart));
    }

    @Test
    void theZoneOfTheClockIsTheZoneOfTheRealm() {
        Clock clock = BusinessDay.clock("Asia/Tokyo", null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertEquals(ZoneId.of("Asia/Tokyo"), clock.getZone());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aRealmMustSetItsTimeZone(String timeZone) {
        for (String value : new String[] {timeZone, null}) {
            FuhitoException e = assertThrows(FuhitoException.class,
                    () -> BusinessDay.clock(value, null, Clock.systemUTC()));
            assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
            assertEquals("realm.time_zone_missing", e.code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tokyo", "Asia/Nowhere", "JST+9", "9"})
    void rejectsAnUnknownTimeZone(String timeZone) {
        FuhitoException e = assertThrows(FuhitoException.class,
                () -> BusinessDay.clock(timeZone, null, Clock.systemUTC()));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("realm.time_zone_invalid", e.code());
        assertEquals(Map.of("value", timeZone), e.params());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "4:00", "04:00:00", "24:00", "04:60", "four", "0400"})
    void rejectsABadDayStart(String dayStart) {
        FuhitoException e = assertThrows(FuhitoException.class,
                () -> BusinessDay.clock("Asia/Tokyo", dayStart, Clock.systemUTC()));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("realm.day_start_invalid", e.code());
        assertEquals(Map.of("value", dayStart), e.params());
    }
}

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

    private static final Clock NOON_UTC = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC);

    private static LocalDate dateOf(BusinessDay.Rule rule) {
        return LocalDate.now(rule.clock(NOON_UTC));
    }

    @Test
    void anOrgUnitCanReplaceTheTimeZoneOrTheDayStartOrBoth() {
        // 01:00 on April 1 in Tokyo, and 12:00 on March 31 in New York.
        BusinessDay.Rule realm = BusinessDay.rule("Asia/Tokyo", "00:30");

        assertEquals(LocalDate.of(2026, 4, 1), dateOf(realm));
        assertEquals(realm, realm.with(null, null));
        assertEquals(realm, realm.with(" ", ""));
        assertEquals(LocalDate.of(2026, 3, 31), dateOf(realm.with("America/New_York", null)));
        assertEquals(LocalDate.of(2026, 3, 31), dateOf(realm.with(null, "04:00")));
        assertEquals(LocalDate.of(2026, 3, 30), dateOf(realm.with(" America/New_York ", " 13:00 ")));
        assertEquals(new BusinessDay.Rule(ZoneId.of("Asia/Tokyo"), java.time.LocalTime.of(0, 30)), realm);
    }

    @Test
    void rejectsBadSettingsOfAnOrgUnit() {
        BusinessDay.Rule realm = BusinessDay.rule("Asia/Tokyo", null);

        FuhitoException zone = assertThrows(FuhitoException.class, () -> realm.with("Tokyo", null));
        assertEquals("org_unit.time_zone_invalid", zone.code());
        assertEquals(Map.of("value", "Tokyo"), zone.params());
        FuhitoException start = assertThrows(FuhitoException.class, () -> realm.with(null, "24:00"));
        assertEquals("org_unit.day_start_invalid", start.code());
        assertEquals(Map.of("value", "24:00"), start.params());
    }
}

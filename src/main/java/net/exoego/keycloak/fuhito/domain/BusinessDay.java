package net.exoego.keycloak.fuhito.domain;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;

/**
 * Decides what "today" is for a realm.
 * The date comes from the time zone of the realm, never from the time zone of the server.
 * A realm can also say at which time of day the date changes, for example 04:00.
 */
public final class BusinessDay {

    // Strict, so that 24:00 is not read as 00:00.
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private BusinessDay() {
    }

    /**
     * Builds a clock whose date is the date of the realm.
     * {@code LocalDate.now(clock)} gives the date. The instant of the clock is moved back by the day start, so it
     * must not be used as a time stamp.
     *
     * @param timeZone the time zone of the realm, for example {@code Asia/Tokyo}
     * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null or blank for 00:00
     * @param base the clock that gives the real time
     * @return the clock of the realm
     * @throws FuhitoException when the time zone is missing or unknown, or the day start has a bad form
     */
    public static Clock clock(String timeZone, String dayStart, Clock base) {
        if (timeZone == null || timeZone.isBlank()) {
            throw FuhitoException.conflict("realm.time_zone_missing", Map.of());
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            throw FuhitoException.conflict("realm.time_zone_invalid", Map.of("value", timeZone));
        }
        LocalTime start = LocalTime.MIDNIGHT;
        if (dayStart != null && !dayStart.isBlank()) {
            try {
                start = LocalTime.parse(dayStart.strip(), TIME);
            } catch (DateTimeParseException e) {
                throw FuhitoException.conflict("realm.day_start_invalid", Map.of("value", dayStart));
            }
        }
        // Before the day start, the date is still the date of the day before.
        return Clock.offset(base.withZone(zone), Duration.ofSeconds(-start.toSecondOfDay()));
    }
}

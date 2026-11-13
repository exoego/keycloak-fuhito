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
 * Decides what "today" is for a realm, and for an org unit that has its own settings.
 * The date comes from a time zone that is set, never from the time zone of the server.
 * A setting can also say at which time of day the date changes, for example 04:00.
 */
public final class BusinessDay {

    // Strict, so that 24:00 is not read as 00:00.
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private BusinessDay() {
    }

    /**
     * Where a date is decided, and when the date changes there.
     *
     * @param zone the time zone
     * @param dayStart the time of day when the date changes
     */
    public record Rule(ZoneId zone, LocalTime dayStart) {

        /**
         * Builds a clock whose date is the date of this rule.
         * {@code LocalDate.now(clock)} gives the date. The instant of the clock is moved back by the day start,
         * so it must not be used as a time stamp.
         *
         * @param base the clock that gives the real time
         * @return the clock
         */
        public Clock clock(Clock base) {
            // Before the day start, the date is still the date of the day before.
            return Clock.offset(base.withZone(zone), Duration.ofSeconds(-dayStart.toSecondOfDay()));
        }

        /**
         * Returns this rule with the settings of an org unit on top. A missing setting keeps the value of this rule.
         *
         * @param timeZone the time zone of the org unit, or null or blank for none
         * @param newDayStart the day start of the org unit as {@code HH:mm}, or null or blank for none
         * @return the rule for the org unit
         * @throws FuhitoException when the time zone is unknown or the day start has a bad form
         */
        public Rule with(String timeZone, String newDayStart) {
            return new Rule(
                    blank(timeZone) ? zone : parseZone(timeZone, "org_unit.time_zone_invalid"),
                    blank(newDayStart) ? dayStart : parseTime(newDayStart, "org_unit.day_start_invalid"));
        }
    }

    /**
     * Builds the rule of a realm.
     *
     * @param timeZone the time zone of the realm, for example {@code Asia/Tokyo}
     * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null or blank for 00:00
     * @return the rule
     * @throws FuhitoException when the time zone is missing or unknown, or the day start has a bad form
     */
    public static Rule rule(String timeZone, String dayStart) {
        if (blank(timeZone)) {
            throw FuhitoException.conflict("realm.time_zone_missing", Map.of());
        }
        return new Rule(parseZone(timeZone, "realm.time_zone_invalid"),
                blank(dayStart) ? LocalTime.MIDNIGHT : parseTime(dayStart, "realm.day_start_invalid"));
    }

    /**
     * Builds a clock whose date is the date of a realm.
     *
     * @param timeZone the time zone of the realm
     * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null or blank for 00:00
     * @param base the clock that gives the real time
     * @return the clock of the realm
     * @throws FuhitoException when the time zone is missing or unknown, or the day start has a bad form
     */
    public static Clock clock(String timeZone, String dayStart, Clock base) {
        return rule(timeZone, dayStart).clock(base);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ZoneId parseZone(String value, String errorCode) {
        try {
            return ZoneId.of(value.strip());
        } catch (DateTimeException e) {
            throw FuhitoException.conflict(errorCode, Map.of("value", value));
        }
    }

    private static LocalTime parseTime(String value, String errorCode) {
        try {
            return LocalTime.parse(value.strip(), TIME);
        } catch (DateTimeParseException e) {
            throw FuhitoException.conflict(errorCode, Map.of("value", value));
        }
    }
}

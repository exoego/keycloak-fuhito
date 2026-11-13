package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The dates when a fact is true.
 * The range is half-open. It includes {@code from} and does not include {@code to}.
 *
 * @param from the first date of the period
 * @param to the first date after the period, or null when the period has no end
 */
public record ValidPeriod(LocalDate from, LocalDate to) {

    /**
     * Creates a period.
     *
     * @throws IllegalArgumentException when {@code to} is not after {@code from}
     */
    public ValidPeriod {
        Objects.requireNonNull(from, "from");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from: " + from + " to " + to);
        }
    }

    /**
     * Creates a period with no end.
     *
     * @param from the first date of the period
     * @return the period
     */
    public static ValidPeriod startingAt(LocalDate from) {
        return new ValidPeriod(from, null);
    }

    /**
     * Tells whether the period has no end.
     *
     * @return true when the period has no end
     */
    public boolean isOpenEnded() {
        return to == null;
    }

    /**
     * Tells whether the date is in the period.
     *
     * @param date the date
     * @return true when the date is in the period
     */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && endsAfter(date);
    }

    /**
     * Tells whether the two periods share at least one date.
     *
     * @param other the other period
     * @return true when the periods share a date
     */
    public boolean overlaps(ValidPeriod other) {
        return endsAfter(other.from) && other.endsAfter(from);
    }

    /**
     * Tells whether every date of the other period is in this period.
     *
     * @param other the other period
     * @return true when this period covers the other period
     */
    public boolean encloses(ValidPeriod other) {
        if (other.from.isBefore(from)) {
            return false;
        }
        if (to == null) {
            return true;
        }
        return other.to != null && !other.to.isAfter(to);
    }

    /**
     * Returns this period with a new end.
     *
     * @param newTo the first date after the period, or null for no end
     * @return the period with the new end
     * @throws IllegalArgumentException when {@code newTo} is not after {@code from}
     */
    public ValidPeriod endingAt(LocalDate newTo) {
        return new ValidPeriod(from, newTo);
    }

    /**
     * Splits the period in two at a date. The second part starts at the date.
     *
     * @param date a date in the period that is not its first date
     * @return the part before the date and the part from the date
     * @throws IllegalArgumentException when the date does not split the period in two
     */
    public List<ValidPeriod> splitAt(LocalDate date) {
        if (!date.isAfter(from) || !endsAfter(date)) {
            throw new IllegalArgumentException("date must be inside the period and after its start: " + date);
        }
        return List.of(new ValidPeriod(from, date), new ValidPeriod(date, to));
    }

    private boolean endsAfter(LocalDate date) {
        return to == null || to.isAfter(date);
    }
}

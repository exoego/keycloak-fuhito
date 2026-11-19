package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The names of a reference item for a period. The periods of one item have no gaps, and the last one has no end.
 *
 * @param id the ID
 * @param refItemId the ID of the item
 * @param validFrom the first date of the names, or null when they hold from the start
 * @param validTo the first date after the names, or null when they have no end
 * @param names the names by language tag
 */
public record RefItemPeriod(String id, String refItemId, LocalDate validFrom, LocalDate validTo,
        Localized<RefItemName> names) {

    /**
     * Creates the names of a period.
     *
     * @throws IllegalArgumentException when {@code validTo} is not after {@code validFrom}
     */
    public RefItemPeriod {
        if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom: " + validFrom + " to " + validTo);
        }
    }

    /**
     * Tells whether the names hold on a date.
     *
     * @param date the date
     * @return true when the date is in the period
     */
    public boolean holdsOn(LocalDate date) {
        return (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || date.isBefore(validTo));
    }

    /**
     * Returns these names with a new end.
     *
     * @param to the first date after the names, or null for no end
     * @return the names with the new end
     */
    public RefItemPeriod endingAt(LocalDate to) {
        return new RefItemPeriod(id, refItemId, validFrom, to, names);
    }

    /**
     * Returns other names for the same period.
     *
     * @param newNames the names by language tag
     * @return the names of the period
     */
    public RefItemPeriod renamed(Localized<RefItemName> newNames) {
        return new RefItemPeriod(id, refItemId, validFrom, validTo, newNames);
    }

    /**
     * Picks the names that a read of a date shows. An item has no dates of its own, and data of any date may use
     * it. So a date before the first period shows the first names.
     *
     * @param all every period of one item, oldest first
     * @param date the date
     * @return the names of the date
     */
    public static RefItemPeriod on(List<RefItemPeriod> all, LocalDate date) {
        return all.stream().filter(names -> names.holdsOn(date)).findFirst().orElse(all.getFirst());
    }
}

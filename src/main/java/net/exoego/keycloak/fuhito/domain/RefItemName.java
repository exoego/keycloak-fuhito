package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The names of a reference item for a period. The periods of one item have no gaps, and the last one has no end.
 *
 * @param id the ID
 * @param refItemId the ID of the item
 * @param validFrom the first date of the names, or null when they hold from the start
 * @param validTo the first date after the names, or null when they have no end
 * @param name the default name
 * @param names the names in other languages, by language tag
 */
public record RefItemName(String id, String refItemId, LocalDate validFrom, LocalDate validTo, String name,
        Map<String, String> names) {

    /**
     * Creates the names of a period.
     *
     * @throws IllegalArgumentException when {@code validTo} is not after {@code validFrom}
     */
    public RefItemName {
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
    public RefItemName endingAt(LocalDate to) {
        return new RefItemName(id, refItemId, validFrom, to, name, names);
    }

    /**
     * Returns other names for the same period.
     *
     * @param newName the default name
     * @param newNames the names in other languages
     * @return the names of the period
     */
    public RefItemName renamed(String newName, Map<String, String> newNames) {
        return new RefItemName(id, refItemId, validFrom, validTo, newName, newNames);
    }

    /**
     * Picks the names that a read of a date shows. An item has no dates of its own, and data of any date may use
     * it. So a date before the first period shows the first names.
     *
     * @param all every period of one item, oldest first
     * @param date the date
     * @return the names of the date
     */
    public static RefItemName on(List<RefItemName> all, LocalDate date) {
        return all.stream().filter(names -> names.holdsOn(date)).findFirst().orElse(all.getFirst());
    }
}

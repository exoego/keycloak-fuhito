package net.exoego.keycloak.fuhito.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;

/**
 * Removes the propagated changes of a realm that are older than the days that the realm keeps them.
 * The entries of writes are kept for good.
 */
public final class ChangeLogCleanup {

    /**
     * The number of days that a realm with no setting keeps a propagated change.
     */
    public static final int DEFAULT_DAYS = 90;

    private ChangeLogCleanup() {
    }

    /**
     * Removes the propagated changes that are older than the days of the setting. It reads the setting while the realm
     * cannot be removed. So the setting belongs to the realm whose entries it removes, also when the realm was removed
     * and made again with the same ID.
     *
     * @param store the change log of the realm
     * @param days reads the number of days of the realm, or null for {@value #DEFAULT_DAYS}
     * @param now the time now
     * @return the number of removed entries
     * @throws FuhitoException with {@code change_log.keep_days_invalid} when the days are no whole number of 0 or more,
     *         also for a realm with nothing to lock
     */
    public static long removeOld(ChangeLogStore store, Supplier<String> days, Instant now) {
        boolean locked = store.lockRealmAgainstRemoval();
        Instant before = now.minus(Duration.ofDays(days(days.get())));
        // The database takes no time that far back, and no entry is from before 1970.
        return !locked || before.isBefore(Instant.EPOCH) ? 0 : store.removePropagated(before);
    }

    private static int days(String setting) {
        if (setting == null) {
            return DEFAULT_DAYS;
        }
        int days;
        try {
            days = Integer.parseInt(setting.strip());
        } catch (NumberFormatException e) {
            days = -1;
        }
        if (days < 0) {
            throw FuhitoException.conflict("change_log.keep_days_invalid", Map.of("value", setting));
        }
        return days;
    }
}

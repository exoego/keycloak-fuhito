package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.EffectiveChange;

/**
 * Reads of what takes effect on which date in one realm.
 */
public interface EffectiveChangeStore {

    /**
     * Lists a page of the resources with a period that starts or ends in a range of dates.
     * A resource comes once for each such date. The order is the date, then the kind, then the ID.
     *
     * @param from the first date of the range
     * @param to the first date after the range
     * @param first the number of entries to skip
     * @param max the largest number of entries to return
     * @return the entries of the page
     */
    List<EffectiveChange> list(LocalDate from, LocalDate to, int first, int max);
}

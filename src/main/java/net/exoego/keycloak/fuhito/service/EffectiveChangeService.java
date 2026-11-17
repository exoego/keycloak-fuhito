package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.EffectiveChange;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.EffectiveChangeStore;

/**
 * Reads of what takes effect on which date in one realm.
 */
public class EffectiveChangeService {

    private final EffectiveChangeStore store;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store the reads of the realm
     * @param clock the clock that decides what "today" is
     */
    public EffectiveChangeService(EffectiveChangeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Lists a page of the resources with a period that starts or ends in a range of dates.
     *
     * @param from the first date of the range as {@code YYYY-MM-DD}, or null for today
     * @param to the first date after the range as {@code YYYY-MM-DD}, or null for the day after {@code from}
     * @param page the part of the list to return
     * @return the entries of the page, ordered by date, then kind, then ID
     * @throws FuhitoException when a date is bad or the range is empty
     */
    public List<EffectiveChange> list(String from, String to, Page page) {
        ValidPeriod range = range(from, to);
        return store.list(range.from(), range.to(), page.first(), page.max());
    }

    /**
     * Counts the entries that {@link #list} lists on all pages together.
     *
     * @param from the first date of the range as {@code YYYY-MM-DD}, or null for today
     * @param to the first date after the range as {@code YYYY-MM-DD}, or null for the day after {@code from}
     * @return the number of entries
     * @throws FuhitoException when a date is bad or the range is empty
     */
    public long count(String from, String to) {
        ValidPeriod range = range(from, to);
        return store.count(range.from(), range.to());
    }

    private ValidPeriod range(String from, String to) {
        LocalDate first = from == null ? LocalDate.now(clock) : Checks.date("from", from);
        LocalDate after = to == null ? first.plusDays(1) : Checks.date("to", to);
        return Checks.period(first, after);
    }
}

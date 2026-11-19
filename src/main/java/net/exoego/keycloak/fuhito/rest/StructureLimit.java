package net.exoego.keycloak.fuhito.rest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import net.exoego.keycloak.fuhito.service.OrgUnitSuccessions;
import net.exoego.keycloak.fuhito.service.Today;

/**
 * Keeps a caller who may read only the org structure from reading it too far ahead.
 * A plan for a reorganization can be secret until it is announced.
 */
final class StructureLimit {

    private final RequestContext context;
    private final Today today;

    /**
     * Creates the limit for one request.
     *
     * @param context the request and its caller
     * @param today what today is for the realm
     */
    StructureLimit(RequestContext context, Today today) {
        this.context = context;
        this.today = today;
    }

    /**
     * Requires that the caller may read the structure on a date.
     *
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the same date
     */
    String checked(String asOf) {
        Optional<LocalDate> lastDay = lastDay();
        if (asOf != null && lastDay.isPresent() && Checks.optionalDate("asOf", asOf).isAfter(lastDay.get())) {
            throw tooFarAhead(lastDay.get());
        }
        return asOf;
    }

    /**
     * Requires that the caller may read the structure on every date, as a history shows it.
     */
    void requireEveryDate() {
        Optional<LocalDate> lastDay = lastDay();
        if (lastDay.isPresent()) {
            throw tooFarAhead(lastDay.get());
        }
    }

    /**
     * Leaves out the successions after the last date that the caller may read.
     *
     * @param successions the successions of an org unit
     * @return the successions that the caller may read
     */
    OrgUnitSuccessions readable(OrgUnitSuccessions successions) {
        return lastDay()
                .map(day -> new OrgUnitSuccessions(until(successions.predecessors(), day),
                        until(successions.successors(), day)))
                .orElse(successions);
    }

    /**
     * Leaves out an end of the details of an org unit after the last date that the caller may read. Such an end
     * is the date of a later change.
     *
     * @param state the org unit on a date
     * @return the org unit as the caller may read it
     */
    OrgUnitState readable(OrgUnitState state) {
        OrgUnitVersion version = state.version();
        LocalDate to = version.period().to();
        Optional<LocalDate> lastDay = lastDay();
        if (to == null || lastDay.isEmpty() || !to.isAfter(lastDay.get())) {
            return state;
        }
        return new OrgUnitState(state.unit(), version.withPeriod(ValidPeriod.startingAt(version.period().from())),
                state.unitType(), state.parentId(), state.legalEntity(), state.lateral(), state.places());
    }

    /**
     * Leaves out an end of the names of a reference item after the last date that the caller may read. Such an
     * end is the date of later names.
     *
     * @param item the item with the names of a date
     * @return the item as the caller may read it
     */
    RefItem readable(RefItem item) {
        LocalDate to = item.validTo();
        Optional<LocalDate> lastDay = lastDay();
        if (to == null || lastDay.isEmpty() || !to.isAfter(lastDay.get())) {
            return item;
        }
        return item.withNames(new RefItemName(null, item.id(), item.validFrom(), null, item.name(), item.names()));
    }

    /**
     * Tells whether an org unit starts after the last date that the caller may read.
     *
     * @param firstDate the first date of the org unit
     * @return true when the caller may not know of the unit yet
     */
    boolean hides(LocalDate firstDate) {
        return lastDay().filter(firstDate::isAfter).isPresent();
    }

    private static List<OrgUnitSuccession> until(List<OrgUnitSuccession> links, LocalDate day) {
        return links.stream().filter(link -> !link.effectiveDate().isAfter(day)).toList();
    }

    private Optional<LocalDate> lastDay() {
        OptionalInt days = context.structureDaysAhead();
        return days.isPresent() ? Optional.of(today.ofRealm().plusDays(days.getAsInt())) : Optional.empty();
    }

    private static FuhitoException tooFarAhead(LocalDate lastDay) {
        return FuhitoException.forbidden("structure.too_far_ahead", Map.of("lastDay", lastDay.toString()));
    }
}

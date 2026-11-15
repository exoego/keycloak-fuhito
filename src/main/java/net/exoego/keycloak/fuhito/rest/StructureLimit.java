package net.exoego.keycloak.fuhito.rest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
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

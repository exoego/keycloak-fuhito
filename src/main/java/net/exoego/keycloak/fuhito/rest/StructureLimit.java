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
     * Tells whether the caller is limited. A limited caller cannot write, so it needs no ETag, and an ETag of the
     * whole history would tell it that a later change was stored.
     *
     * @return true when the realm limits the caller
     */
    boolean active() {
        return lastDay().isPresent();
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
        return new OrgUnitState(state.unit(), new OrgUnitVersion(version.id(), version.orgUnitId(),
                ValidPeriod.startingAt(version.period().from()), version.code(), version.name(), version.names(),
                version.shortName(), version.shortNames(), version.phoneticName(), version.unitTypeId(),
                version.displayOrder(), version.attributes()), state.unitType(), state.parentId(),
                state.legalEntity(), state.lateral());
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

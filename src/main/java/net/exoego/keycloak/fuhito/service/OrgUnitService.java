package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the org units of one realm.
 */
public class OrgUnitService {

    private final OrgUnitStore store;
    private final RefItemStore refItems;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param clock the clock that decides what "today" is
     */
    public OrgUnitService(OrgUnitStore store, RefItemStore refItems, Clock clock) {
        this.store = store;
        this.refItems = refItems;
        this.clock = clock;
    }

    /**
     * Creates an org unit that starts on a date and has no end.
     *
     * @param request the values of the request
     * @return the unit as it is on its first date
     * @throws FuhitoException when a value is bad, the code is taken, or the parent is not valid
     */
    public OrgUnitState create(NewOrgUnit request) {
        Checks.required("body", request);
        LocalDate validFrom = Checks.date("validFrom", request.validFrom());
        ValidPeriod period = ValidPeriod.startingAt(validFrom);
        String code = Checks.optionalCode("code", request.code());
        if (code != null && store.findUnitByCode(code).isPresent()) {
            throw FuhitoException.conflict("org_unit.code_taken", Map.of("code", code));
        }
        RefItem unitType = request.unitType() == null ? null : activeUnitType(request.unitType());
        if (request.parentId() != null) {
            requireParentCovers(request.parentId(), period);
        }

        OrgUnit unit = new OrgUnit(newId(), code, request.externalId(), request.source(), null);
        OrgUnitVersion version = version(newId(), unit.id(), period, new OrgUnitChange(request.validFrom(),
                request.name(), request.names(), request.shortName(), request.shortNames(), request.phoneticName(),
                request.unitType(), request.displayOrder(), request.attributes()), unitType);
        store.insertUnit(unit);
        store.insertVersion(version);
        if (request.parentId() != null) {
            store.insertLink(new OrgUnitLink(newId(), store.defaultHierarchyId(), unit.id(), request.parentId(),
                    period));
        }
        return new OrgUnitState(unit, version, request.unitType(), request.parentId());
    }

    /**
     * Replaces the details of an org unit from a date. The unit keeps its ID.
     * The change lasts until the next change that is already stored, or has no end.
     * A change on the first date of stored details corrects those details.
     *
     * @param id the ID
     * @param request the new details and their first date
     * @return the unit as it is on that date
     * @throws FuhitoException when the unit does not exist on the date or a value is bad
     */
    public OrgUnitState changeDetails(String id, OrgUnitChange request) {
        Checks.required("body", request);
        LocalDate from = Checks.date("validFrom", request.validFrom());
        requireUnit(id);
        OrgUnitVersion current = versionOn(id, from);
        RefItem unitType = request.unitType() == null ? null : unitTypeForChange(request.unitType(), current);

        if (current.period().from().equals(from)) {
            store.updateVersion(version(current.id(), id, current.period(), request, unitType));
        } else {
            List<ValidPeriod> parts = current.period().splitAt(from);
            // Build the new version first, so that a bad value fails before anything is written.
            OrgUnitVersion next = version(newId(), id, parts.get(1), request, unitType);
            store.updateVersion(withPeriod(current, parts.get(0)));
            store.insertVersion(next);
        }
        return get(id, request.validFrom());
    }

    /**
     * Returns an org unit as it is on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the unit on the date
     * @throws FuhitoException when the unit does not exist, or does not exist on the date
     */
    public OrgUnitState get(String id, String asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : Checks.optionalDate("asOf", asOf);
        OrgUnit unit = requireUnit(id);
        OrgUnitVersion version = versionOn(id, date);
        String unitType = version.unitTypeId() == null ? null
                : refItems.find(version.unitTypeId()).map(RefItem::code).orElse(null);
        String parentId = store.parentLinks(store.defaultHierarchyId(), id).stream()
                .filter(link -> link.period().contains(date))
                .map(OrgUnitLink::parentId)
                .findFirst()
                .orElse(null);
        return new OrgUnitState(unit, version, unitType, parentId);
    }

    private OrgUnit requireUnit(String id) {
        return store.findUnit(id)
                .orElseThrow(() -> FuhitoException.notFound("org_unit.not_found", Map.of("id", id)));
    }

    private OrgUnitVersion versionOn(String id, LocalDate date) {
        return store.versions(id).stream()
                .filter(v -> v.period().contains(date))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("org_unit.not_valid_on",
                        Map.of("id", id, "date", date.toString())));
    }

    private static OrgUnitVersion version(String versionId, String unitId, ValidPeriod period, OrgUnitChange details,
            RefItem unitType) {
        return new OrgUnitVersion(versionId, unitId, period,
                Checks.name("name", details.name()),
                Checks.localizedNames("names", details.names()),
                Checks.optionalName("shortName", details.shortName()),
                Checks.localizedNames("shortNames", details.shortNames()),
                Checks.optionalName("phoneticName", details.phoneticName()),
                unitType == null ? null : unitType.id(),
                details.displayOrder() == null ? 0 : details.displayOrder(),
                details.attributes() == null ? Map.of() : details.attributes());
    }

    private static OrgUnitVersion withPeriod(OrgUnitVersion v, ValidPeriod period) {
        return new OrgUnitVersion(v.id(), v.orgUnitId(), period, v.name(), v.names(), v.shortName(), v.shortNames(),
                v.phoneticName(), v.unitTypeId(), v.displayOrder(), v.attributes());
    }

    // A unit may keep a unit type that was deactivated after the unit got it.
    private RefItem unitTypeForChange(String code, OrgUnitVersion current) {
        return refItems.findByCode(RefKind.UNIT_TYPE, code)
                .filter(item -> item.active() || item.id().equals(current.unitTypeId()))
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown", Map.of("unitType", code)));
    }

    private RefItem activeUnitType(String code) {
        return refItems.findByCode(RefKind.UNIT_TYPE, code)
                .filter(RefItem::active)
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown", Map.of("unitType", code)));
    }

    // The parent must exist on every date of the child. Versions of one unit have no gaps.
    private void requireParentCovers(String parentId, ValidPeriod period) {
        List<OrgUnitVersion> versions = store.versions(parentId);
        if (versions.isEmpty()) {
            throw FuhitoException.invalid("org_unit.parent_unknown", Map.of("parentId", parentId));
        }
        ValidPeriod life = new ValidPeriod(versions.get(0).period().from(),
                versions.get(versions.size() - 1).period().to());
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("org_unit.parent_not_valid",
                    Map.of("parentId", parentId, "validFrom", period.from().toString()));
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}

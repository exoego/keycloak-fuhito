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
        OrgUnitVersion version = new OrgUnitVersion(newId(), unit.id(), period,
                Checks.name("name", request.name()),
                Checks.localizedNames("names", request.names()),
                Checks.optionalName("shortName", request.shortName()),
                Checks.localizedNames("shortNames", request.shortNames()),
                Checks.optionalName("phoneticName", request.phoneticName()),
                unitType == null ? null : unitType.id(),
                request.displayOrder() == null ? 0 : request.displayOrder(),
                request.attributes() == null ? Map.of() : request.attributes());
        store.insertUnit(unit);
        store.insertVersion(version);
        if (request.parentId() != null) {
            store.insertLink(new OrgUnitLink(newId(), store.defaultHierarchyId(), unit.id(), request.parentId(),
                    period));
        }
        return new OrgUnitState(unit, version, request.unitType(), request.parentId());
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
        OrgUnit unit = store.findUnit(id)
                .orElseThrow(() -> FuhitoException.notFound("org_unit.not_found", Map.of("id", id)));
        OrgUnitVersion version = store.versions(id).stream()
                .filter(v -> v.period().contains(date))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("org_unit.not_valid_on",
                        Map.of("id", id, "date", date.toString())));
        String unitType = version.unitTypeId() == null ? null
                : refItems.find(version.unitTypeId()).map(RefItem::code).orElse(null);
        String parentId = store.parentLinks(store.defaultHierarchyId(), id).stream()
                .filter(link -> link.period().contains(date))
                .map(OrgUnitLink::parentId)
                .findFirst()
                .orElse(null);
        return new OrgUnitState(unit, version, unitType, parentId);
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

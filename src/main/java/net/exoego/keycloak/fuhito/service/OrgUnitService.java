package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the org units of one realm.
 */
public class OrgUnitService {

    private final OrgUnitStore store;
    private final AssignmentStore assignments;
    private final RefItemStore refItems;
    private final Today today;

    /**
     * Creates the service.
     *
     * @param store the storage of org units of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param clock the clock that decides what "today" is
     */
    public OrgUnitService(OrgUnitStore store, AssignmentStore assignments, RefItemStore refItems, Clock clock) {
        this(store, assignments, refItems, Today.of(clock));
    }

    /**
     * Creates the service for a realm where an org unit can have its own time settings.
     *
     * @param store the storage of org units of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param today what today is for the realm and for each org unit
     */
    public OrgUnitService(OrgUnitStore store, AssignmentStore assignments, RefItemStore refItems, Today today) {
        this.store = store;
        this.assignments = assignments;
        this.refItems = refItems;
        this.today = today;
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
            store.updateVersion(withPeriod(current, parts.getFirst()));
            store.insertVersion(next);
        }
        return get(id, request.validFrom());
    }

    /**
     * Gives an org unit a new parent in the default hierarchy from a date, or makes it a root.
     * The change lasts until the next parent change that is already stored, or has no end.
     *
     * @param id the ID
     * @param parentId the ID of the new parent, or null to make the unit a root
     * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
     * @return the unit as it is on that date
     * @throws FuhitoException when the unit or the parent is not valid, or the move would make a cycle
     */
    public OrgUnitState move(String id, String parentId, String validFrom) {
        LocalDate from = Checks.date("validFrom", validFrom);
        requireUnit(id);
        versionOn(id, from);
        String hierarchyId = store.defaultHierarchyId();
        List<OrgUnitLink> links = store.parentLinks(hierarchyId, id);
        OrgUnitLink current = links.stream().filter(l -> l.period().contains(from)).findFirst().orElse(null);
        List<OrgUnitVersion> versions = store.versions(id);
        // With no later parent change, the new parent lasts as long as the unit does.
        LocalDate until = current != null ? current.period().to() : links.stream()
                .map(l -> l.period().from())
                .filter(start -> start.isAfter(from))
                .findFirst()
                .orElse(versions.getLast().period().to());
        ValidPeriod period = new ValidPeriod(from, until);

        if (parentId != null) {
            requireParentCovers(parentId, period);
            requireNoCycle(hierarchyId, id, parentId, period);
        }
        if (current != null && current.period().from().equals(from)) {
            if (parentId == null) {
                store.deleteLink(current.id());
            } else {
                store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, parentId, period));
            }
            return get(id, validFrom);
        }
        if (current != null) {
            store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, current.parentId(),
                    current.period().endingAt(from)));
        }
        if (parentId != null) {
            store.insertLink(new OrgUnitLink(newId(), hierarchyId, id, parentId, period));
        }
        return get(id, validFrom);
    }

    /**
     * Ends an org unit. Its history stays readable for earlier dates.
     *
     * @param id the ID
     * @param validTo the first date on which the unit no longer exists, as {@code YYYY-MM-DD}
     * @return the unit as it is on its last date
     * @throws FuhitoException when the unit has an end already, has changes, children, or assignments on or after
     *         the date, or does not exist before the date
     */
    public OrgUnitState end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        requireUnit(id);
        List<OrgUnitVersion> versions = store.versions(id);
        OrgUnitVersion last = versions.getLast();
        if (last.period().to() != null) {
            throw FuhitoException.conflict("org_unit.already_ended",
                    Map.of("id", id, "validTo", last.period().to().toString()));
        }
        if (!to.isAfter(versions.getFirst().period().from())) {
            throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", to.toString()));
        }
        List<OrgUnitLink> parentLinks = store.parentLinks(store.defaultHierarchyId(), id);
        // A parent link that ends later means the unit becomes a root on that date. That is a later change too.
        boolean laterChange = !to.isAfter(last.period().from())
                || parentLinks.stream().anyMatch(l -> !l.period().from().isBefore(to)
                        || (l.period().to() != null && l.period().to().isAfter(to)));
        if (laterChange) {
            throw FuhitoException.conflict("org_unit.later_changes_exist", Map.of("id", id, "validTo", validTo));
        }
        long children = store.childLinks(id).stream()
                .filter(l -> l.period().endsAfter(to))
                .map(OrgUnitLink::childId)
                .distinct()
                .count();
        if (children > 0) {
            throw FuhitoException.conflict("org_unit.has_children",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(children)));
        }

        long staffed = assignments.rowsOfOrgUnit(id).stream()
                .filter(row -> row.period().endsAfter(to))
                .map(Assignment::assignmentId)
                .distinct()
                .count();
        if (staffed > 0) {
            throw FuhitoException.conflict("org_unit.has_assignments",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(staffed)));
        }

        store.updateVersion(withPeriod(last, last.period().endingAt(to)));
        parentLinks.stream()
                .filter(l -> l.period().isOpenEnded())
                .forEach(l -> store.updateLink(new OrgUnitLink(l.id(), l.hierarchyId(), l.childId(), l.parentId(),
                        l.period().endingAt(to))));
        return get(id, to.minusDays(1).toString());
    }

    // Walks up from the new parent on every date of the period. Meeting the moved unit means a cycle.
    private void requireNoCycle(String hierarchyId, String movedId, String ancestorId, ValidPeriod period) {
        if (ancestorId.equals(movedId)) {
            throw FuhitoException.conflict("org_unit.cycle", Map.of("id", movedId));
        }
        for (OrgUnitLink link : store.parentLinks(hierarchyId, ancestorId)) {
            link.period().intersection(period)
                    .ifPresent(shared -> requireNoCycle(hierarchyId, movedId, link.parentId(), shared));
        }
    }

    /**
     * Sets the time zone and the day start of an org unit. The units below it use the same, unless one of them
     * has its own. A missing value means that the unit uses the value from above, or from the realm.
     *
     * @param id the ID
     * @param timeZone the time zone, for example {@code America/New_York}, or null or blank for none
     * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null or blank for none
     * @return the unit with the new settings
     * @throws FuhitoException when the unit does not exist, the time zone is unknown, or the day start is bad
     */
    public OrgUnit setTime(String id, String timeZone, String dayStart) {
        OrgUnit unit = requireUnit(id);
        String zone = timeZone == null || timeZone.isBlank() ? null : timeZone.strip();
        String start = dayStart == null || dayStart.isBlank() ? null : dayStart.strip();
        try {
            // Any rule will do for the check. Only the two new values are parsed.
            new BusinessDay.Rule(ZoneOffset.UTC, LocalTime.MIDNIGHT).with(zone, start);
        } catch (FuhitoException e) {
            throw FuhitoException.invalid(e.code(), e.params()).causedBy(e);
        }
        OrgUnit changed = new OrgUnit(unit.id(), unit.code(), unit.externalId(), unit.source(),
                unit.keycloakOrganizationId(), zone, start);
        store.updateUnit(changed);
        return changed;
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
        LocalDate date = dateOrToday(asOf, id);
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

    /**
     * Lists the org units that exist on a date, in the default hierarchy.
     * Each parent comes before its children.
     * Siblings follow the display order, then the phonetic name, then the name.
     *
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param rootsOnly true to return only the units with no parent
     * @return the units on the date
     */
    public List<OrgUnitState> list(String asOf, boolean rootsOnly) {
        OrgUnitTree tree = tree(asOf, null);
        return rootsOnly ? tree.roots() : tree.all();
    }

    /**
     * Lists the direct children of an org unit on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the children on the date
     * @throws FuhitoException when the unit does not exist, or does not exist on the date
     */
    public List<OrgUnitState> children(String id, String asOf) {
        requireUnit(id);
        return tree(asOf, id).children(id);
    }

    /**
     * Lists an org unit and everything below it on a date. Each parent comes before its children.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the unit and its descendants on the date
     * @throws FuhitoException when the unit does not exist, or does not exist on the date
     */
    public List<OrgUnitState> subtree(String id, String asOf) {
        requireUnit(id);
        return tree(asOf, id).subtree(id);
    }

    /**
     * Lists the units from the root down to an org unit on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @return the path, with the root first and the unit last
     * @throws FuhitoException when the unit does not exist, or does not exist on the date
     */
    public List<OrgUnitState> path(String id, String asOf) {
        requireUnit(id);
        return tree(asOf, id).path(id);
    }

    /**
     * Returns everything that is stored about an org unit over time.
     *
     * @param id the ID
     * @return the details of every period and the parent of every period
     * @throws FuhitoException when the unit does not exist
     */
    public OrgUnitHistory history(String id) {
        OrgUnit unit = requireUnit(id);
        Map<String, String> unitTypeCodes = new HashMap<>();
        refItems.list(RefKind.UNIT_TYPE, true).forEach(item -> unitTypeCodes.put(item.id(), item.code()));
        List<OrgUnitState> versions = store.versions(id).stream()
                .map(version -> new OrgUnitState(unit, version, unitTypeCodes.get(version.unitTypeId()), null))
                .toList();
        return new OrgUnitHistory(unit, versions, store.parentLinks(store.defaultHierarchyId(), id));
    }

    private OrgUnitTree tree(String asOf, String orgUnitId) {
        return new OrgUnitTree(store, refItems, dateOrToday(asOf, orgUnitId));
    }

    // A read about one org unit uses the date of that unit. A read about the whole realm uses the date of the realm.
    private LocalDate dateOrToday(String asOf, String orgUnitId) {
        if (asOf != null) {
            return Checks.optionalDate("asOf", asOf);
        }
        return orgUnitId == null ? today.ofRealm() : today.ofUnit(orgUnitId);
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
        ValidPeriod life = new ValidPeriod(versions.getFirst().period().from(),
                versions.getLast().period().to());
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("org_unit.parent_not_valid",
                    Map.of("parentId", parentId, "validFrom", period.from().toString()));
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}

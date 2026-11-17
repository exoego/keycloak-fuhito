package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the org units of one realm.
 */
public class OrgUnitService {

    private final AttributeRules attributeRules;
    private final OrgUnitStore store;
    private final ExternalIdWrites externalIds;
    private final AssignmentStore assignments;
    private final RefItemStore refItems;
    private final Today today;

    /**
     * Creates the service.
     *
     * @param store the storage of org units of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param clock the clock that decides what "today" is
     */
    public OrgUnitService(OrgUnitStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, Clock clock) {
        this(store, externalIds, assignments, refItems, Today.of(clock));
    }

    /**
     * Creates the service for a realm where an org unit can have its own time settings.
     *
     * @param store the storage of org units of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param today what today is for the realm and for each org unit
     */
    public OrgUnitService(OrgUnitStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, Today today) {
        this(store, externalIds, assignments, refItems, today, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param today the rule that decides what "today" is for each org unit
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public OrgUnitService(OrgUnitStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, Today today, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.store = store;
        this.externalIds = new ExternalIdWrites(externalIds, ExternalId.Kind.ORG_UNIT);
        this.assignments = assignments;
        this.refItems = refItems;
        this.today = today;
    }

    /**
     * Creates an org unit that starts on a date and has no end.
     *
     * @param request the values of the request. The unit keeps the source as the system that made it.
     * @return the unit as it is on its first date
     * @throws FuhitoException when a value is bad, the code or the external ID is taken, or the parent is not
     *         valid
     */
    public OrgUnitState create(NewOrgUnit request) {
        Checks.required("body", request);
        LocalDate validFrom = Checks.date("validFrom", request.validFrom());
        ValidPeriod period = ValidPeriod.startingAt(validFrom);
        RefItem unitType = request.unitType() == null ? null : activeUnitType(request.unitType());
        ExternalId.Pair externalId = externalIds.check(null, request.source(), request.externalId());
        OrgUnit unit = new OrgUnit(newId(), Checks.optionalCode("source", request.source()), null);
        requireFreeCode(Checks.optionalCode("code", request.code()), unit.id(), period);
        if (request.parentId() != null) {
            requireParentCovers(request.parentId(), period);
            requireLineParent(unit.id(), List.of(new Nature(period, unitType != null && unitType.lateral())),
                    request.parentId(), natures(request.parentId()), period);
        }

        OrgUnitVersion version = version(newId(), unit.id(), period, new OrgUnitChange(request.validFrom(),
                request.code(), request.name(), request.names(), request.shortName(), request.shortNames(),
                request.phoneticName(), request.unitType(), request.workLocation(), request.costCenter(),
                request.displayOrder(), request.attributes()), unitType, null);
        store.insertUnit(unit);
        externalIds.store(unit.id(), externalId);
        store.insertVersion(version);
        if (request.parentId() != null) {
            store.insertLink(new OrgUnitLink(newId(), store.defaultHierarchyId(), unit.id(), request.parentId(),
                    period));
        }
        return UnitPlaces.on(List.of(OrgUnitState.of(unit, version, unitType, request.parentId())), store,
                refItems, validFrom).getFirst();
    }

    /**
     * Replaces the details of an org unit from a date, and stores the ID that a system has for the unit.
     *
     * @param id the ID
     * @param request the new details and their first date
     * @param source the name of the system, or null
     * @param externalId the ID in that system, or null to keep the IDs as they are
     * @return the unit as it is on that date
     * @throws FuhitoException when the unit does not exist on the date, a value is bad, or the ID names another
     *         unit
     */
    public OrgUnitState changeDetails(String id, OrgUnitChange request, String source, String externalId) {
        ExternalId.Pair pair = externalIds.check(id, source, externalId);
        OrgUnitState state = changeDetails(id, request);
        externalIds.store(id, pair);
        return state;
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
        if (unitType == null || !unitType.legalEntity()) {
            // An employment needs its employer to be a legal entity on its first date.
            long employments = store.employmentsStartingIn(id, from, current.period().to());
            if (employments > 0) {
                throw FuhitoException.conflict("org_unit.is_employer",
                        Map.of("id", id, "validFrom", request.validFrom(), "count", Long.toString(employments)));
            }
        }

        ValidPeriod changed = current.period().from().equals(from) ? current.period()
                : current.period().splitAt(from).get(1);
        requireFreeCode(Checks.optionalCode("code", request.code()), id, changed);
        List<Nature> nature = List.of(new Nature(changed, unitType != null && unitType.lateral()));
        String hierarchyId = store.defaultHierarchyId();
        for (OrgUnitLink link : store.parentLinks(hierarchyId, id)) {
            requireLineParent(id, nature, link.parentId(), natures(link.parentId()), link.period());
        }
        for (OrgUnitLink link : store.childLinks(hierarchyId, id)) {
            requireLineParent(link.childId(), natures(link.childId()), id, nature, link.period());
        }

        if (current.period().from().equals(from)) {
            store.updateVersion(version(current.id(), id, current.period(), request, unitType, current));
        } else {
            List<ValidPeriod> parts = current.period().splitAt(from);
            // Build the new version first, so that a bad value fails before anything is written.
            OrgUnitVersion next = version(newId(), id, parts.get(1), request, unitType, current);
            store.updateVersion(withPeriod(current, parts.getFirst()));
            store.insertVersion(next);
        }
        return get(id, request.validFrom());
    }

    /**
     * Gives an org unit a new parent in the default hierarchy from a date, or makes it a root.
     *
     * @param id the ID
     * @param parentId the ID of the new parent, or null to make the unit a root
     * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
     * @return the unit as it is on that date
     * @throws FuhitoException when the unit or the parent is not valid, or the move would make a cycle
     */
    public OrgUnitState move(String id, String parentId, String validFrom) {
        return move(id, parentId, validFrom, null);
    }

    /**
     * Gives an org unit a new parent in a hierarchy from a date, or takes its parent away.
     * The change lasts until the next parent change that is already stored, or has no end.
     *
     * @param id the ID
     * @param parentId the ID of the new parent, or null for no parent
     * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @return the unit as it is on that date, with its parent in that hierarchy
     * @throws FuhitoException when the unit, the parent, or the hierarchy is not valid, or the move would make a
     *         cycle
     */
    public OrgUnitState move(String id, String parentId, String validFrom, String hierarchy) {
        LocalDate from = Checks.date("validFrom", validFrom);
        requireUnit(id);
        versionOn(id, from);
        String hierarchyId = hierarchyId(hierarchy);
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
            if (hierarchyId.equals(store.defaultHierarchyId())) {
                requireLineParent(id, natures(id), parentId, natures(parentId), period);
            }
        }
        if (current != null && current.period().from().equals(from)) {
            if (parentId == null) {
                store.deleteLink(current.id());
            } else {
                store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, parentId, period));
            }
            return state(id, from, hierarchyId);
        }
        if (current != null) {
            store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, current.parentId(),
                    current.period().endingAt(from)));
        }
        if (parentId != null) {
            store.insertLink(new OrgUnitLink(newId(), hierarchyId, id, parentId, period));
        }
        return state(id, from, hierarchyId);
    }

    /**
     * Ends an org unit, or moves the end that it has. Its history stays readable for earlier dates.
     * An earlier end needs the same as a first end. A later end lengthens the last details and the parents that
     * ended with the unit, and each parent must exist on the new dates.
     *
     * @param id the ID
     * @param validTo the first date on which the unit no longer exists, as {@code YYYY-MM-DD}
     * @return the unit as it is on its last date
     * @throws FuhitoException when the unit has changes, children, assignments, or roles on or after the date, does
     *         not exist before the date, has units that follow it, or a parent does not exist on the new dates
     */
    public OrgUnitState end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        requireUnit(id);
        List<OrgUnitVersion> versions = store.versions(id);
        OrgUnitVersion last = versions.getLast();
        LocalDate current = last.period().to();
        if (!to.isAfter(versions.getFirst().period().from())) {
            throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", to.toString()));
        }
        if (to.equals(current)) {
            return get(id, to.minusDays(1).toString());
        }
        requireLaterThanPredecessors(id, to);
        if (current != null) {
            requireNoSuccessors(id);
        }
        if (current != null && to.isAfter(current)) {
            lengthen(id, last, to);
            return get(id, to.minusDays(1).toString());
        }
        List<OrgUnitLink> parentLinks = store.parentLinks(id);
        // A parent link that ends later means the unit becomes a root on that date. That is a later change too.
        // A link that ends with the unit moves with it.
        boolean laterChange = !to.isAfter(last.period().from())
                || parentLinks.stream().anyMatch(l -> !l.period().from().isBefore(to)
                        || (l.period().to() != null && l.period().to().isAfter(to) && !l.period().to().equals(current)));
        if (laterChange) {
            throw FuhitoException.conflict("org_unit.later_changes_exist", Map.of("id", id, "validTo", validTo));
        }
        long children = store.childrenHoldingFrom(id, to);
        if (children > 0) {
            throw FuhitoException.conflict("org_unit.has_children",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(children)));
        }

        long employed = store.employmentsHoldingFrom(id, to);
        if (employed > 0) {
            throw FuhitoException.conflict("org_unit.has_employments",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(employed)));
        }

        long staffed = assignments.assignmentsHoldingFrom(id, to);
        if (staffed > 0) {
            throw FuhitoException.conflict("org_unit.has_assignments",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(staffed)));
        }

        long roles = store.unitRolesHoldingFrom(id, to);
        if (roles > 0) {
            throw FuhitoException.conflict("org_unit.has_unit_roles",
                    Map.of("id", id, "validTo", validTo, "count", Long.toString(roles)));
        }

        store.updateVersion(withPeriod(last, last.period().endingAt(to)));
        parentLinks.stream()
                .filter(l -> l.period().isOpenEnded() || l.period().to().equals(current))
                .forEach(l -> store.updateLink(new OrgUnitLink(l.id(), l.hierarchyId(), l.childId(), l.parentId(),
                        l.period().endingAt(to))));
        return get(id, to.minusDays(1).toString());
    }

    /**
     * Removes the end of an org unit, for example to take back a closure. The last details and the parents that
     * ended with the unit get no end either.
     *
     * @param id the ID
     * @return the unit as it is on the last first date of its details
     * @throws FuhitoException when the unit does not exist, has units that follow it, or a parent ends
     */
    public OrgUnitState removeEnd(String id) {
        requireUnit(id);
        OrgUnitVersion last = store.versions(id).getLast();
        if (last.period().to() != null) {
            requireNoSuccessors(id);
            lengthen(id, last, null);
        }
        return get(id, last.period().from().toString());
    }

    // The parents that ended with the unit go on with it, so each must exist on the new dates.
    private void lengthen(String id, OrgUnitVersion last, LocalDate to) {
        LocalDate current = last.period().to();
        List<OrgUnitLink> ending = store.parentLinks(id).stream()
                .filter(l -> current.equals(l.period().to()))
                .toList();
        ValidPeriod added = new ValidPeriod(current, to);
        requireFreeCode(last.code(), id, added);
        for (OrgUnitLink link : ending) {
            requireParentCovers(link.parentId(), added);
            if (link.hierarchyId().equals(store.defaultHierarchyId())) {
                requireLineParent(id, List.of(new Nature(added, natures(id).getLast().lateral())), link.parentId(),
                        natures(link.parentId()), added);
            }
        }
        store.updateVersion(withPeriod(last, last.period().endingAt(to)));
        ending.forEach(l -> store.updateLink(new OrgUnitLink(l.id(), l.hierarchyId(), l.childId(), l.parentId(),
                l.period().endingAt(to))));
    }

    // A unit that follows others must exist on the date of each succession.
    private void requireLaterThanPredecessors(String id, LocalDate to) {
        Optional<OrgUnitSuccession> followed = store.successions(id).stream()
                .filter(link -> link.successorId().equals(id) && !to.isAfter(link.effectiveDate()))
                .findFirst();
        if (followed.isPresent()) {
            throw FuhitoException.conflict("org_unit.follows_others",
                    Map.of("id", id, "date", followed.get().effectiveDate().toString()));
        }
    }

    // A merger or a split names the date of the end. The end stays where the successions put it.
    private void requireNoSuccessors(String id) {
        if (store.successions(id).stream().anyMatch(succession -> succession.predecessorId().equals(id))) {
            throw FuhitoException.conflict("org_unit.has_successors", Map.of("id", id));
        }
    }

    /**
     * Ends an org unit, and records the units that follow it from the same date.
     * One unit that follows is a succession. A split has several. A merger is several ends that name one unit.
     * The people and the units below must move first, the same as for an end.
     *
     * @param id the ID of the unit that ends
     * @param validTo the first date on which the unit no longer exists, as {@code YYYY-MM-DD}
     * @param successorIds the IDs of the units that follow it. Each must exist on the date.
     * @return the unit as it is on its last date
     * @throws FuhitoException when no unit follows, a unit that follows is the unit itself or does not exist on the
     *         date, or the unit cannot end
     */
    public OrgUnitState succeed(String id, String validTo, List<String> successorIds) {
        LocalDate to = Checks.date("validTo", validTo);
        if (successorIds == null || successorIds.isEmpty()) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "successorIds"));
        }
        requireUnit(id);
        Set<String> successors = new LinkedHashSet<>(successorIds);
        for (String successorId : successors) {
            Checks.required("successorIds", successorId);
            if (successorId.equals(id)) {
                throw FuhitoException.invalid("org_unit.successor_self", Map.of("id", id));
            }
            get(successorId, validTo);
        }
        OrgUnitVersion last = store.versions(id).getLast();
        if (last.period().to() != null) {
            throw FuhitoException.conflict("org_unit.already_ended",
                    Map.of("id", id, "validTo", last.period().to().toString()));
        }
        OrgUnitState ended = end(id, validTo);
        successors.forEach(successorId -> store.insertSuccession(
                new OrgUnitSuccession(newId(), id, successorId, to)));
        return ended;
    }

    /**
     * Returns the org units that an org unit follows, and the ones that follow it.
     *
     * @param id the ID
     * @return the links before and after the unit, oldest first, and by the ID of the other unit for one date
     * @throws FuhitoException when the unit does not exist
     */
    public OrgUnitSuccessions successions(String id) {
        requireUnit(id);
        List<OrgUnitSuccession> all = store.successions(id);
        return new OrgUnitSuccessions(all.stream().filter(link -> link.successorId().equals(id)).toList(),
                all.stream().filter(link -> link.predecessorId().equals(id)).toList());
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
        OrgUnit changed = new OrgUnit(unit.id(), unit.source(), unit.keycloakOrganizationId(), zone, start);
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
        return get(id, asOf, null);
    }

    /**
     * Returns an org unit as it is on a date, with its parent in a hierarchy.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @return the unit on the date
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public OrgUnitState get(String id, String asOf, String hierarchy) {
        return state(id, dateOrToday(asOf, id), hierarchyId(hierarchy));
    }

    private OrgUnitState state(String id, LocalDate date, String hierarchyId) {
        OrgUnit unit = requireUnit(id);
        OrgUnitVersion version = versionOn(id, date);
        RefItem unitType = version.unitTypeId() == null ? null : refItems.find(version.unitTypeId()).orElse(null);
        String parentId = store.parentLinks(hierarchyId, id).stream()
                .filter(link -> link.period().contains(date))
                .map(OrgUnitLink::parentId)
                .findFirst()
                .orElse(null);
        return UnitPlaces.on(List.of(OrgUnitState.of(unit, version, unitType, parentId)), store, refItems, date)
                .getFirst();
    }

    /**
     * Lists a page of the org units with no parent on a date, in a hierarchy.
     * They follow the display order, then the phonetic name, then the name.
     * Every unit is in the default hierarchy. Another hierarchy has only the units with a parent or a child in it.
     *
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param page the part of the list to return
     * @return the roots of the page on the date
     * @throws FuhitoException when the date is bad or the hierarchy does not exist
     */
    public List<OrgUnitState> roots(String asOf, String hierarchy, Boolean lateral, Page page) {
        LocalDate date = dateOrToday(asOf, null);
        String hierarchyId = hierarchyId(hierarchy);
        boolean linkedOnly = !hierarchyId.equals(store.defaultHierarchyId());
        return states(store.childrenOn(hierarchyId, null, linkedOnly, lateral, date, page.first(), page.max()), date);
    }

    /**
     * Lists a page of the direct children of an org unit on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param page the part of the list to return
     * @return the children of the page on the date
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public List<OrgUnitState> children(String id, String asOf, String hierarchy, Boolean lateral, Page page) {
        LocalDate date = dateOfUnit(id, asOf);
        return states(store.childrenOn(hierarchyId(hierarchy), id, false, lateral, date, page.first(),
                page.max()), date);
    }

    /**
     * Lists a page of an org unit and everything below it on a date. Each parent comes before its children.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @param page the part of the list to return
     * @return the units of the page on the date
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public List<OrgUnitState> subtree(String id, String asOf, String hierarchy, Boolean lateral, Page page) {
        LocalDate date = dateOfUnit(id, asOf);
        return states(store.treeOn(hierarchyId(hierarchy), id, lateral, date, page.first(), page.max()), date);
    }

    /**
     * The values of a search of org units. They are not checked yet. A value that is null keeps everything.
     *
     * @param q a part of a name, a short name, or the phonetic name, in any language, with no regard to case
     * @param code the code
     * @param unitType the code of the unit type
     * @param lateral true for lateral units only, false for line units only
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     */
    public record Search(String q, String code, String unitType, Boolean lateral, String asOf) {
    }

    /**
     * Lists a page of the org units of a date that match a search, each with its parent in the default
     * hierarchy. They follow the display order, then the phonetic name, then the name.
     *
     * @param search what to keep and the date
     * @param page the part of the list to return
     * @return the units of the page
     * @throws FuhitoException when a value is bad or the unit type is unknown
     */
    public List<OrgUnitState> search(Search search, Page page) {
        LocalDate date = dateOrToday(search.asOf(), null);
        return states(store.searchOn(store.defaultHierarchyId(), storeSearch(search), date, page.first(),
                page.max()), date);
    }

    /**
     * Counts the org units of a date that match a search.
     *
     * @param search what to keep and the date
     * @return the number of units
     * @throws FuhitoException when a value is bad or the unit type is unknown
     */
    public long countSearch(Search search) {
        return store.countSearchOn(storeSearch(search), dateOrToday(search.asOf(), null));
    }

    private OrgUnitStore.Search storeSearch(Search search) {
        String unitTypeId = search.unitType() == null ? null : refItems.findByCode(RefKind.UNIT_TYPE,
                        search.unitType())
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown",
                        Map.of("unitType", search.unitType())));
        String text = search.q() == null || search.q().isBlank() ? null : search.q().strip();
        return new OrgUnitStore.Search(text, search.code(), unitTypeId, search.lateral());
    }

    /**
     * Lists a page of the org units that are legal entities on a date. An org unit is one when its unit type
     * says so. The order is the display order, then the phonetic name, then the name.
     *
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param page the part of the list to return
     * @return the legal entities of the page on the date, each with its parent in the default hierarchy
     */
    public List<OrgUnitState> legalEntities(String asOf, Page page) {
        LocalDate date = dateOrToday(asOf, null);
        return states(store.legalEntitiesOn(store.defaultHierarchyId(), date, page.first(), page.max()), date);
    }

    private LocalDate dateOfUnit(String id, String asOf) {
        requireUnit(id);
        LocalDate date = dateOrToday(asOf, id);
        versionOn(id, date);
        return date;
    }

    private List<OrgUnitState> states(List<PlacedOrgUnit> placed, LocalDate date) {
        return UnitPlaces.on(OrgUnitPaths.states(placed, refItems), store, refItems, date);
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
        return path(id, asOf, null);
    }

    /**
     * Lists the units from the root down to an org unit on a date, in a hierarchy.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @return the path, with the root first and the unit last
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public List<OrgUnitState> path(String id, String asOf, String hierarchy) {
        requireUnit(id);
        LocalDate date = dateOrToday(asOf, id);
        return UnitPlaces.on(OrgUnitPaths.on(store, refItems, hierarchyId(hierarchy), id, date), store, refItems,
                date);
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
        Map<String, RefItem> unitTypes = new HashMap<>();
        refItems.list(RefKind.UNIT_TYPE, true).forEach(item -> unitTypes.put(item.id(), item));
        List<OrgUnitState> versions = UnitPlaces.own(store.versions(id).stream()
                .map(version -> OrgUnitState.of(unit, version, unitTypes.get(version.unitTypeId()), null))
                .toList(), refItems);
        store.defaultHierarchyId();
        return new OrgUnitHistory(unit, versions, store.parentLinks(id), store.hierarchyCodes());
    }

    /**
     * Returns the first date on which an org unit no longer exists.
     *
     * @param id the ID
     * @return the date, or empty for a unit with no end
     * @throws FuhitoException when the unit does not exist
     */
    public Optional<LocalDate> endOf(String id) {
        requireUnit(id);
        return Optional.ofNullable(store.versions(id).getLast().period().to());
    }

    /**
     * Lists the parent links of a hierarchy on a date.
     *
     * @param hierarchy the code of the hierarchy
     * @param date the date
     * @return the links, each with the unit and its parent
     * @throws FuhitoException when the hierarchy does not exist
     */
    public List<OrgUnitLink> parentLinks(String hierarchy, LocalDate date) {
        return store.linksOn(hierarchyId(hierarchy), date);
    }

    private String hierarchyId(String code) {
        return HierarchyCodes.idOf(store, code);
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

    // A version may keep a work location or a cost center that was deactivated after the unit got it.
    private OrgUnitVersion version(String versionId, String unitId, ValidPeriod period, OrgUnitChange details,
            RefItem unitType, OrgUnitVersion current) {
        return new OrgUnitVersion(versionId, unitId, period,
                Checks.optionalCode("code", details.code()),
                Checks.name("name", details.name()),
                Checks.localizedNames("names", details.names()),
                Checks.optionalName("shortName", details.shortName()),
                Checks.localizedNames("shortNames", details.shortNames()),
                Checks.optionalName("phoneticName", details.phoneticName()),
                unitType == null ? null : unitType.id(),
                idOf(RefKind.WORK_LOCATION, details.workLocation(), current == null ? null : current.workLocationId()),
                idOf(RefKind.COST_CENTER, details.costCenter(), current == null ? null : current.costCenterId()),
                details.displayOrder() == null ? 0 : details.displayOrder(),
                attributeRules.check(AttributeDefinition.Target.ORG_UNIT, details.attributes()));
    }

    private static OrgUnitVersion withPeriod(OrgUnitVersion v, ValidPeriod period) {
        return new OrgUnitVersion(v.id(), v.orgUnitId(), period, v.code(), v.name(), v.names(), v.shortName(),
                v.shortNames(), v.phoneticName(), v.unitTypeId(), v.workLocationId(), v.costCenterId(),
                v.displayOrder(), v.attributes());
    }

    private String idOf(RefKind kind, String code, String currentId) {
        if (code == null) {
            return null;
        }
        return refItems.findByCode(kind, code)
                .filter(item -> item.active() || item.id().equals(currentId))
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", kind.name(), "code", code)));
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

    // No two org units have the same code on the same date. One unit keeps its code over many periods.
    private void requireFreeCode(String code, String unitId, ValidPeriod period) {
        if (code != null && store.versionsWithCode(code).stream()
                .anyMatch(v -> !v.orgUnitId().equals(unitId) && v.period().overlaps(period))) {
            throw FuhitoException.conflict("org_unit.code_taken", Map.of("code", code));
        }
    }

    // Whether a unit is a lateral unit in a period of its details.
    private record Nature(ValidPeriod period, boolean lateral) {
    }

    private List<Nature> natures(String unitId) {
        return store.versions(unitId).stream()
                .map(v -> new Nature(v.period(), v.unitTypeId() != null
                        && refItems.find(v.unitTypeId()).map(RefItem::lateral).orElse(false)))
                .toList();
    }

    // In the default hierarchy, the parent of a line unit is a line unit on every date. A lateral unit may be anywhere.
    private static void requireLineParent(String childId, List<Nature> child, String parentId, List<Nature> parent,
            ValidPeriod link) {
        for (Nature below : child) {
            for (Nature above : parent) {
                if (below.lateral() || !above.lateral()) {
                    continue;
                }
                Optional<ValidPeriod> both = link.intersection(below.period())
                        .flatMap(period -> period.intersection(above.period()));
                if (both.isPresent()) {
                    throw FuhitoException.conflict("org_unit.line_below_lateral", Map.of("id", childId,
                            "parentId", parentId, "date", both.get().from().toString()));
                }
            }
        }
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

package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.PlaceStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the org units of one realm.
 */
public class OrgUnitService {

    private final AttributeRules attributeRules;
    private final OrgUnitStore store;
    private final ExternalIdStore externalIdStore;
    private final ExternalIdWrites externalIds;
    private final AssignmentStore assignments;
    private final RefItemStore refItems;
    private final Items items;
    private final Today today;
    private final DerivedPlaces places;

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
     * Creates the service with a check of free attributes. A write of it finds no read of another resource that it
     * changed.
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
        this(store, externalIds, assignments, refItems, today, attributeRules, PlaceStore.NONE);
    }

    /**
     * Creates the service that also finds the units and the assignments whose work location or cost center that
     * applies a write changed, and counts up their versions. The service of a request needs it.
     *
     * @param store the storage of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param today the rule that decides what "today" is for each org unit
     * @param attributeRules the check of free attributes against the definitions of the realm
     * @param places the reads of what applies below a unit
     */
    public OrgUnitService(OrgUnitStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, Today today, AttributeRules attributeRules, PlaceStore places) {
        this(store, externalIds, assignments, refItems, today, attributeRules, Items.of(refItems),
                new DerivedPlaces(store, places, today, Set.of()));
    }

    private OrgUnitService(OrgUnitStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, Today today, AttributeRules attributeRules, Items items, DerivedPlaces places) {
        this.places = places;
        this.attributeRules = attributeRules;
        this.store = store;
        this.externalIdStore = externalIds;
        this.externalIds = new ExternalIdWrites(externalIds, ExternalId.Kind.ORG_UNIT);
        this.assignments = assignments;
        this.refItems = refItems;
        this.items = items;
        this.today = today;
    }

    // The reference items that the checks of org units read.
    private record Items(Function<String, Optional<RefItem>> byId,
            BiFunction<RefKind, String, Optional<RefItem>> byCode) {

        static Items of(RefItemStore store) {
            return new Items(store::find, store::findByCode);
        }

        Items remembering() {
            Map<String, Optional<RefItem>> ids = new HashMap<>();
            Map<List<String>, Optional<RefItem>> codes = new HashMap<>();
            return new Items(id -> ids.computeIfAbsent(id, byId),
                    (kind, code) -> codes.computeIfAbsent(List.of(kind.name(), code), key -> byCode.apply(kind, code)));
        }
    }

    /**
     * Returns the check of free attributes of these operations, so that an import checks the names of a row with it.
     *
     * @return the check
     */
    AttributeRules attributeRules() {
        return attributeRules;
    }

    /**
     * Returns the same operations that read each reference item once, and the definitions of attributes once for
     * each kind of resource. They suit a list of writes that changes no reference item and no definition.
     *
     * @return the operations
     */
    OrgUnitService remembering() {
        return new OrgUnitService(store, externalIdStore, assignments, refItems, today, attributeRules.remembering(),
                items.remembering(), places);
    }

    /**
     * Returns the same operations for a request that also writes some other units, such as a reorganization. A write
     * here gives those units no propagated change, as each has an entry of its own write.
     *
     * @param unitIds the IDs of the units
     * @return the operations
     */
    OrgUnitService writing(Collection<String> unitIds) {
        return new OrgUnitService(store, externalIdStore, assignments, refItems, today, attributeRules, items,
                places.writing(unitIds));
    }

    /**
     * An org unit after a write of its details or of its parent, and the reads of other resources that the write
     * changed.
     *
     * @param state the unit as it is on the first date of the write
     * @param propagated the units below and the assignments whose work location or cost center that applies
     *         changed on a date. Their versions are counted up.
     */
    public record Written(OrgUnitState state, List<PropagatedChange> propagated) {
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
        NewOrgUnits units = new NewOrgUnits(externalIds);
        String id = units.create(request).unit().id();
        units.insert();
        return units.state(id);
    }

    /**
     * Starts new org units of one list of writes.
     *
     * @param ids finds the unit that an ID of a system names
     * @return the new units
     */
    NewOrgUnits newOrgUnits(ExternalIdWrites.Lookup ids) {
        return new NewOrgUnits(new ExternalIdWrites(externalIdStore, ExternalId.Kind.ORG_UNIT, ids));
    }

    /**
     * The new org units of one list of writes, and the stored ones that its rows name. Each new unit is checked
     * against the stored units and the new ones before it. All of them are stored together.
     */
    final class NewOrgUnits {

        private final ExternalIdWrites ids;
        private final Map<String, Optional<OrgUnit>> units = new HashMap<>();
        private final Map<String, List<OrgUnitVersion>> versions = new HashMap<>();
        private final Map<String, List<OrgUnitLink>> parents = new HashMap<>();
        private final Map<String, List<OrgUnitVersion>> holders = new HashMap<>();
        private final List<OrgUnitState> made = new ArrayList<>();
        private final List<OrgUnitLink> links = new ArrayList<>();
        private final List<ExternalId> rows = new ArrayList<>();
        private final Map<String, OrgUnitState> states = new HashMap<>();
        private String hierarchyId;

        private NewOrgUnits(ExternalIdWrites ids) {
            this.ids = ids;
        }

        /**
         * Reads some units with their versions and their parents in the default hierarchy, and the versions with
         * some codes, with one read for each kind.
         *
         * @param unitIds the IDs of the units
         * @param codes the codes
         */
        void read(Collection<String> unitIds, Collection<String> codes) {
            List<String> unread = unitIds.stream().distinct().filter(id -> !units.containsKey(id)).toList();
            if (!unread.isEmpty()) {
                unread.forEach(id -> units.put(id, Optional.empty()));
                store.findUnits(unread).forEach(unit -> units.put(unit.id(), Optional.of(unit)));
                Map<String, List<OrgUnitVersion>> found = store.versionsOf(unread);
                unread.forEach(id -> versions.put(id, found.getOrDefault(id, List.of())));
                Map<String, List<OrgUnitLink>> linked = store.parentLinksOf(hierarchyId(), unread);
                unread.forEach(id -> parents.put(id, linked.getOrDefault(id, List.of())));
            }
            List<String> unreadCodes = codes.stream().distinct().filter(code -> !holders.containsKey(code)).toList();
            unreadCodes.forEach(code -> holders.put(code, new ArrayList<>()));
            store.versionsWithCodes(unreadCodes).forEach(version -> holders.get(version.code()).add(version));
        }

        /**
         * Finds a unit, a new one of the list included.
         *
         * @param id the ID of the unit
         * @return the unit, or empty when the realm has no such unit
         */
        Optional<OrgUnit> unit(String id) {
            return units.computeIfAbsent(id, store::findUnit);
        }

        /**
         * Lists all versions of a unit, a new one of the list included.
         *
         * @param unitId the ID of the unit
         * @return the versions, oldest first, or none when the realm has no such unit
         */
        List<OrgUnitVersion> versions(String unitId) {
            return versions.computeIfAbsent(unitId, store::versions);
        }

        /**
         * Returns a stored unit as it is on a date on which it exists, as {@link OrgUnitService#get} does. Its work
         * location and its cost center are those of its own version, with none that apply from above.
         *
         * @param id the ID of the unit
         * @param date the date
         * @return the unit on the date
         */
        OrgUnitState stateOn(String id, LocalDate date) {
            OrgUnit unit = unit(id).orElseThrow();
            OrgUnitVersion version = versions(id).stream()
                    .filter(v -> v.period().contains(date))
                    .findFirst()
                    .orElseThrow();
            RefItem unitType = version.unitTypeId() == null ? null
                    : items.byId().apply(version.unitTypeId()).orElse(null);
            String parentId = parents.computeIfAbsent(id, key -> store.parentLinks(hierarchyId(), key)).stream()
                    .filter(link -> link.period().contains(date))
                    .map(OrgUnitLink::parentId)
                    .findFirst()
                    .orElse(null);
            Function<String, String> code = itemId -> itemId == null ? null
                    : items.byId().apply(itemId).map(RefItem::code).orElse(null);
            return OrgUnitState.of(unit, version, unitType, parentId).withPlaces(new LocationAndCost(
                    code.apply(version.workLocationId()), code.apply(version.costCenterId()), null, null));
        }

        /**
         * Returns the ID of the default hierarchy, which the list reads once.
         *
         * @return the ID
         */
        String hierarchyId() {
            if (hierarchyId == null) {
                hierarchyId = store.defaultHierarchyId();
            }
            return hierarchyId;
        }

        /**
         * Runs a write that changes a stored unit. The new units go to the database first, so that the checks of the
         * write see them. A later question about the unit reads it again.
         *
         * @param <T> the type of the result
         * @param id the ID of the unit
         * @param write the write
         * @return the result of the write
         */
        <T> T write(String id, Supplier<T> write) {
            insert();
            try {
                return write.get();
            } finally {
                units.remove(id);
                versions.remove(id);
                parents.remove(id);
                holders.clear();
            }
        }

        /**
         * Checks a new unit as {@link OrgUnitService#create} does, and keeps it for {@link #insert}.
         *
         * @param request the values of the request
         * @return the new unit on its first date, without the work location and the cost center that apply to it
         */
        OrgUnitState create(NewOrgUnit request) {
            Checks.required("body", request);
            LocalDate validFrom = Checks.date("validFrom", request.validFrom());
            ValidPeriod period = ValidPeriod.startingAt(validFrom);
            RefItem unitType = request.unitType() == null ? null : activeUnitType(request.unitType());
            ExternalId.Pair externalId = ids.check(null, request.source(), request.externalId());
            OrgUnit unit = new OrgUnit(newId(), Checks.optionalCode("source", request.source()), null);
            String code = Checks.optionalCode("code", request.code());
            if (code != null) {
                requireFreeCode(holdersOf(code), code, unit.id(), period);
            }
            String parentId = request.parentId();
            if (parentId != null) {
                List<OrgUnitVersion> parent = versions(parentId);
                requireParentCovers(parentId, lifeOf(parent), period);
                requireDefaultLink(unit.id(), List.of(Nature.of(period, unitType)), parentId, natures(parent),
                        period);
            }

            OrgUnitVersion version = version(newId(), unit.id(), period, new OrgUnitChange(request.validFrom(),
                    request.code(), request.primaryLanguage(), request.names(), request.unitType(),
                    request.workLocation(), request.costCenter(), request.displayOrder(), request.attributes()),
                    unitType, null);
            units.put(unit.id(), Optional.of(unit));
            versions.put(unit.id(), List.of(version));
            if (code != null) {
                holdersOf(code).add(version);
            }
            List<OrgUnitLink> link = parentId == null ? List.of()
                    : List.of(new OrgUnitLink(newId(), hierarchyId(), unit.id(), parentId, period));
            parents.put(unit.id(), link);
            links.addAll(link);
            ids.forNew(unit.id(), externalId).ifPresent(rows::add);
            OrgUnitState state = OrgUnitState.of(unit, version, unitType, parentId);
            made.add(state);
            return state;
        }

        private List<OrgUnitVersion> holdersOf(String code) {
            return holders.computeIfAbsent(code, key -> new ArrayList<>(store.versionsWithCodes(List.of(key))));
        }

        /**
         * Stores the new units that are not stored yet, with their versions, their parents, and their IDs of other
         * systems. Then reads the work location and the cost center that apply to each one on its first date.
         */
        void insert() {
            store.insertUnits(made.stream().map(OrgUnitState::unit).toList());
            made.forEach(state -> store.insertVersion(state.version()));
            links.forEach(store::insertLink);
            externalIdStore.insertAll(List.copyOf(rows));
            Map<LocalDate, List<OrgUnitState>> byDate = new LinkedHashMap<>();
            made.forEach(state -> byDate.computeIfAbsent(state.version().period().from(), date -> new ArrayList<>())
                    .add(state));
            byDate.forEach((date, onDate) -> UnitPlaces.on(onDate, store, refItems, date)
                    .forEach(state -> states.put(state.unit().id(), state)));
            made.clear();
            links.clear();
            rows.clear();
        }

        /**
         * Returns a new unit that {@link #insert} stored, as it was on its first date.
         *
         * @param id the ID of the unit
         * @return the unit
         */
        OrgUnitState state(String id) {
            return states.get(id);
        }
    }

    /**
     * Replaces the details of an org unit from a date, and stores the ID that a system has for the unit.
     *
     * @param id the ID
     * @param request the new details and their first date
     * @param hierarchy the code of the hierarchy of the parent of the result, or null for the default one
     * @param source the name of the system, or null
     * @param externalId the ID in that system, or null to keep the IDs as they are
     * @return the unit as it is on that date, with its parent in that hierarchy
     * @throws FuhitoException when the unit or the hierarchy does not exist, the unit does not exist on the date, a
     *         value is bad, or the ID names another unit
     */
    public Written changeDetails(String id, OrgUnitChange request, String hierarchy, String source,
            String externalId) {
        String hierarchyId = hierarchyId(hierarchy);
        ExternalId.Pair pair = externalIds.check(id, source, externalId);
        Written written = changeDetails(id, request, hierarchyId, hierarchy);
        externalIds.store(id, pair);
        return written;
    }

    /**
     * Replaces the details of an org unit from a date. The unit keeps its ID.
     * The change lasts until the next change that is already stored, or has no end.
     * A change on the first date of stored details corrects those details.
     * Another work location or cost center changes what applies to the units below and to the assignments.
     *
     * @param id the ID
     * @param request the new details and their first date
     * @return the unit as it is on that date, and the reads that the change changed
     * @throws FuhitoException when the unit does not exist on the date or a value is bad
     */
    public Written changeDetails(String id, OrgUnitChange request) {
        return changeDetails(id, request, store.defaultHierarchyId(), null);
    }

    /**
     * Tells whether a change of the details can change what applies to other units or to assignments. It can when
     * it gives the unit another work location or cost center than the details of its first date have. A request
     * that cannot be read can, and then fails in the write.
     *
     * @param id the ID
     * @param validFrom the first date of the change, as {@code YYYY-MM-DD}
     * @param workLocation the code of the work location of the change, or null
     * @param costCenter the code of the cost center of the change, or null
     * @return true when the change can change other reads
     */
    public boolean changesPlaces(String id, String validFrom, String workLocation, String costCenter) {
        try {
            OrgUnitVersion current = versionOn(id, Checks.date("validFrom", validFrom));
            Function<String, String> code = UnitPlaces.codes(refItems);
            return !Objects.equals(code.apply(current.workLocationId()), workLocation)
                    || !Objects.equals(code.apply(current.costCenterId()), costCenter);
        } catch (FuhitoException e) {
            return true;
        }
    }

    /**
     * Tells whether a move in a hierarchy can change what applies to other units or to assignments. Only the
     * parents of the default hierarchy pass on the work location and the cost center.
     *
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @return true for the default hierarchy
     */
    public static boolean movesPlaces(String hierarchy) {
        return isDefault(hierarchy);
    }

    private Written changeDetails(String id, OrgUnitChange request, String parentHierarchyId,
            String hierarchy) {
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
        List<Nature> nature = List.of(Nature.of(changed, unitType));
        String hierarchyId = store.defaultHierarchyId();
        for (OrgUnitLink link : store.parentLinks(hierarchyId, id)) {
            requireDefaultLink(id, nature, link.parentId(), natures(link.parentId()), link.period());
        }
        for (OrgUnitLink link : store.childLinks(hierarchyId, id)) {
            requireDefaultLink(link.childId(), natures(link.childId()), id, nature, link.period());
        }

        boolean corrects = current.period().from().equals(from);
        // Build the new version first, so that a bad value fails before anything is written.
        OrgUnitVersion next = version(corrects ? current.id() : newId(), id, changed, request, unitType, current);
        DerivedPlaces.Watch watch = Objects.equals(next.workLocationId(), current.workLocationId())
                && Objects.equals(next.costCenterId(), current.costCenterId()) ? null
                : places.watchDetails(id, changed);
        if (corrects) {
            store.updateVersion(next);
        } else {
            store.updateVersion(current.withPeriod(current.period().splitAt(from).getFirst()));
            store.insertVersion(next);
        }
        return new Written(state(id, from, parentHierarchyId, hierarchy),
                watch == null ? List.of() : watch.afterWrite());
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
    public Written move(String id, String parentId, String validFrom) {
        return move(id, parentId, validFrom, null);
    }

    /**
     * Gives an org unit a new parent in a hierarchy from a date, or takes its parent away.
     * The change lasts until the next parent change that is already stored, or has no end.
     * A move in the default hierarchy changes what applies to the unit, to the units below, and to the assignments.
     *
     * @param id the ID
     * @param parentId the ID of the new parent, or null for no parent
     * @param validFrom the first date under the new parent, as {@code YYYY-MM-DD}
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @return the unit as it is on that date, with its parent in that hierarchy, and the reads that the move changed
     * @throws FuhitoException when the unit, the parent, or the hierarchy is not valid, or the move would make a
     *         cycle
     */
    public Written move(String id, String parentId, String validFrom, String hierarchy) {
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

        boolean inDefault = hierarchyId.equals(store.defaultHierarchyId());
        if (parentId != null) {
            requireParentCovers(parentId, period);
            requireNoCycle(unitId -> store.parentLinks(hierarchyId, unitId), id, parentId, period, new HashSet<>());
            if (inDefault) {
                requireDefaultLink(id, natures(id), parentId, natures(parentId), period);
            }
        }
        DerivedPlaces.Watch watch = inDefault ? places.watchMove(id, period) : null;
        if (current != null && current.period().from().equals(from)) {
            if (parentId == null) {
                store.deleteLink(current.id());
            } else {
                store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, parentId, period));
            }
        } else {
            if (current != null) {
                store.updateLink(new OrgUnitLink(current.id(), hierarchyId, id, current.parentId(),
                        current.period().endingAt(from)));
            }
            if (parentId != null) {
                store.insertLink(new OrgUnitLink(newId(), hierarchyId, id, parentId, period));
            }
        }
        return new Written(state(id, from, hierarchyId, hierarchy), watch == null ? List.of() : watch.afterWrite());
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
        requireLaterThanPredecessors(id, new ValidPeriod(versions.getFirst().period().from(), to));
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

        store.updateVersion(last.withPeriod(last.period().endingAt(to)));
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
                requireDefaultLink(id, natures(List.of(last.withPeriod(added))), link.parentId(),
                        natures(link.parentId()), added);
            }
        }
        store.updateVersion(last.withPeriod(last.period().endingAt(to)));
        ending.forEach(l -> store.updateLink(new OrgUnitLink(l.id(), l.hierarchyId(), l.childId(), l.parentId(),
                l.period().endingAt(to))));
    }

    private void requireLaterThanPredecessors(String id, ValidPeriod life) {
        store.successions(id).stream()
                .filter(link -> link.successorId().equals(id))
                .forEach(link -> requireFollows(id, life, link.effectiveDate()));
    }

    /**
     * Requires that a unit that follows others exists on the date of a succession.
     *
     * @param id the ID of the unit that follows
     * @param life the life of the unit
     * @param date the date of the succession
     * @throws FuhitoException when the unit does not exist on the date
     */
    static void requireFollows(String id, ValidPeriod life, LocalDate date) {
        if (!life.contains(date)) {
            throw FuhitoException.conflict("org_unit.follows_others", Map.of("id", id, "date", date.toString()));
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

    /**
     * Requires that a parent link puts no unit below itself. It walks up from the parent on every date of the link.
     * Meeting the child means a cycle. A walk that meets a unit of its path again found a cycle above the child,
     * which the links of that cycle report.
     *
     * @param parentLinks gives the parent links of a unit in the hierarchy of the link
     * @param movedId the ID of the child
     * @param ancestorId the ID of the parent, or of a unit above it
     * @param period the dates of the link that the walk is on
     * @param path the units of the walk so far
     * @throws FuhitoException when the child is above itself on a date
     */
    static void requireNoCycle(Function<String, List<OrgUnitLink>> parentLinks, String movedId, String ancestorId,
            ValidPeriod period, Set<String> path) {
        if (ancestorId.equals(movedId)) {
            throw FuhitoException.conflict("org_unit.cycle", Map.of("id", movedId));
        }
        if (!path.add(ancestorId)) {
            return;
        }
        for (OrgUnitLink link : parentLinks.apply(ancestorId)) {
            link.period().intersection(period)
                    .ifPresent(shared -> requireNoCycle(parentLinks, movedId, link.parentId(), shared, path));
        }
        path.remove(ancestorId);
    }

    /**
     * Sets the time zone and the day start of an org unit. The units below it use the same, unless one of them
     * has its own. A missing value means that the unit uses the value from above, or from the realm.
     *
     * @param id the ID
     * @param timeZone the time zone, for example {@code America/New_York}, or null for none
     * @param dayStart the time of day when the date changes, as {@code HH:mm}, or null for none
     * @return the unit with the new settings
     * @throws FuhitoException when the unit does not exist, a value is empty or only spaces, the time zone is
     *         unknown, or the day start is bad
     */
    public OrgUnit setTime(String id, String timeZone, String dayStart) {
        OrgUnit unit = requireUnit(id);
        Checks.optionalText("timeZone", timeZone);
        Checks.optionalText("dayStart", dayStart);
        String zone = timeZone == null ? null : timeZone.strip();
        String start = dayStart == null ? null : dayStart.strip();
        try {
            // Any rule will do for the check. Only the two new values are parsed.
            new BusinessDay.Rule(ZoneOffset.UTC, LocalTime.MIDNIGHT).with(zone, start);
        } catch (FuhitoException e) {
            throw FuhitoException.invalid(e.code(), e.params()).causedBy(e);
        }
        store.updateUnit(new OrgUnit(unit.id(), unit.source(), unit.keycloakOrganizationId(), zone, start,
                unit.version()));
        return requireUnit(id);
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
        return state(id, dateOrToday(asOf, id), hierarchyId(hierarchy), hierarchy);
    }

    private OrgUnitState state(String id, LocalDate date, String hierarchyId, String hierarchy) {
        OrgUnit unit = requireUnit(id);
        OrgUnitVersion version = versionOn(id, date);
        RefItem unitType = version.unitTypeId() == null ? null
                : items.byId().apply(version.unitTypeId()).orElse(null);
        String parentId = store.parentLinks(hierarchyId, id).stream()
                .filter(link -> link.period().contains(date))
                .map(OrgUnitLink::parentId)
                .findFirst()
                .orElse(null);
        return UnitPlaces.on(List.of(OrgUnitState.of(unit, version, unitType, parentId)
                .withHierarchy(codeOf(hierarchy))), store, refItems, date).getFirst();
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
        return states(store.childrenOn(hierarchyId(hierarchy), id, lateral, date, page.first(), page.max()), date,
                hierarchy);
    }

    /**
     * Counts the direct children of an org unit on a date that {@link #children} lists on all pages together.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @return the number of children
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public long countChildren(String id, String asOf, String hierarchy, Boolean lateral) {
        LocalDate date = dateOfUnit(id, asOf);
        return store.countChildrenOn(hierarchyId(hierarchy), id, lateral, date);
    }

    /**
     * Lists a page of an org unit and everything below it on a date. Each parent comes before its children.
     * A rollup unit is not in the default hierarchy, so the list there is empty for it.
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
        List<OrgUnitState> units = states(store.treeOn(hierarchyId(hierarchy), id, lateral, date, page.first(),
                page.max()), date, hierarchy);
        // A rollup unit is not in the default hierarchy. It can only be the unit of the read there, with no child.
        return isDefault(hierarchy) ? units.stream().filter(unit -> !unit.rollup()).toList() : units;
    }

    /**
     * Counts the units that {@link #subtree} lists on all pages together.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param hierarchy the code of the hierarchy, or null for the default one
     * @param lateral true for lateral units only, false for line units only, or null for both
     * @return the number of units
     * @throws FuhitoException when the unit or the hierarchy does not exist, or the unit does not exist on the date
     */
    public long countSubtree(String id, String asOf, String hierarchy, Boolean lateral) {
        LocalDate date = dateOfUnit(id, asOf);
        long count = store.countTreeOn(hierarchyId(hierarchy), id, lateral, date);
        // A rollup unit has no child in the default hierarchy, so only a count of one can be the rollup unit.
        return count == 1 && isDefault(hierarchy) && isRollup(versionOn(id, date)) ? 0 : count;
    }

    /**
     * The values of a list of org units. They are not checked yet. A value that is null keeps everything.
     *
     * @param q a part of a name, a short name, or the phonetic name, in any language, with no regard to case
     * @param code the code
     * @param unitType the code of the unit type
     * @param lateral true for lateral units only, false for line units only
     * @param rollup true for rollup units only, false for the other units
     * @param legalEntity true for legal entities only, false for the other units
     * @param roots true for the roots of the hierarchy only, false for every unit
     * @param hierarchy the code of the hierarchy of the parents and of the roots, or null for the default one
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     */
    public record Search(String q, String code, String unitType, Boolean lateral, Boolean rollup,
            Boolean legalEntity, boolean roots, String hierarchy, String asOf) {

        /**
         * Makes a search that keeps every unit of a date.
         *
         * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
         * @return the search
         */
        public static Search all(String asOf) {
            return new Search(null, null, null, null, null, null, false, null, asOf);
        }
    }

    /**
     * Lists a page of the org units of a date that match a search, at any depth, each with its parent in the
     * hierarchy of the search. They follow the display order, then the primary names, then the ID.
     * Every unit but a rollup unit is in the default hierarchy. The roots of another hierarchy are the units with no
     * parent and with a child in it.
     *
     * @param search what to keep, the hierarchy, and the date
     * @param page the part of the list to return
     * @param after the cursor after which the page starts, or null
     * @return the units of the page
     * @throws FuhitoException when a value is bad, the unit type or the hierarchy is unknown, or the unit of the
     *         cursor does not exist on the date
     */
    public List<OrgUnitState> list(Search search, Page page, String after) {
        LocalDate date = dateOrToday(search.asOf(), null);
        String hierarchyId = hierarchyId(search.hierarchy());
        OrgUnitStore.Search filter = storeSearch(search, hierarchyId);
        OrgUnitVersion last = after == null ? null : versionAfter(after, date);
        return states(store.searchOn(hierarchyId, filter, date, last, page.first(), page.max()), date,
                search.hierarchy());
    }

    /**
     * Counts the org units of a date that match a search.
     *
     * @param search what to keep, the hierarchy, and the date
     * @return the number of units
     * @throws FuhitoException when a value is bad, or the unit type or the hierarchy is unknown
     */
    public long count(Search search) {
        String hierarchyId = hierarchyId(search.hierarchy());
        return store.countSearchOn(hierarchyId, storeSearch(search, hierarchyId), dateOrToday(search.asOf(), null));
    }

    private OrgUnitStore.Search storeSearch(Search search, String hierarchyId) {
        String unitTypeId = search.unitType() == null ? null : refItems.findByCode(RefKind.UNIT_TYPE,
                        search.unitType())
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown",
                        Map.of("unitType", search.unitType())));
        String text = search.q() == null || search.q().isBlank() ? null : search.q().strip();
        OrgUnitStore.Roots roots = !search.roots() ? null
                : hierarchyId.equals(store.defaultHierarchyId()) ? OrgUnitStore.Roots.DEFAULT
                : OrgUnitStore.Roots.LINKED;
        return new OrgUnitStore.Search(text, search.code(), unitTypeId, search.lateral(), search.rollup(),
                search.legalEntity(), roots);
    }

    // The cursor has only the ID. The details of the unit on the date give the other values of the order.
    private OrgUnitVersion versionAfter(String after, LocalDate date) {
        String id = Cursor.decode(Cursor.ORG_UNITS, after).getFirst();
        return store.versions(id).stream()
                .filter(version -> version.period().contains(date))
                .findFirst()
                .orElseThrow(() -> FuhitoException.invalid("invalid.cursor_unit",
                        Map.of("field", "after", "id", id, "date", date.toString())));
    }

    private LocalDate dateOfUnit(String id, String asOf) {
        requireUnit(id);
        LocalDate date = dateOrToday(asOf, id);
        versionOn(id, date);
        return date;
    }

    // The code of the default hierarchy never changes.
    private static boolean isDefault(String hierarchy) {
        return hierarchy == null || hierarchy.equals(Hierarchy.DEFAULT_CODE);
    }

    private static String codeOf(String hierarchy) {
        return hierarchy == null ? Hierarchy.DEFAULT_CODE : hierarchy;
    }

    private boolean isRollup(OrgUnitVersion version) {
        return version.unitTypeId() != null
                && items.byId().apply(version.unitTypeId()).map(RefItem::rollup).orElse(false);
    }

    private List<OrgUnitState> states(List<PlacedOrgUnit> placed, LocalDate date, String hierarchy) {
        return UnitPlaces.on(OrgUnitPaths.states(placed, refItems).stream()
                .map(state -> state.withHierarchy(codeOf(hierarchy)))
                .toList(), store, refItems, date);
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
     * A rollup unit is not in the default hierarchy, so its path there is empty.
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
        List<OrgUnitState> path = OrgUnitPaths.on(store, refItems, hierarchyId(hierarchy), id, date);
        // A rollup unit is not in the default hierarchy.
        if (path.getLast().rollup() && isDefault(hierarchy)) {
            return List.of();
        }
        return UnitPlaces.on(path.stream().map(state -> state.withHierarchy(codeOf(hierarchy))).toList(), store,
                refItems, date);
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
                .map(version -> OrgUnitState.of(unit, version, unitTypes.get(version.unitTypeId()), null)
                        .withHierarchy(null))
                .toList(), refItems);
        store.defaultHierarchyId();
        return new OrgUnitHistory(unit, versions, store.parentLinks(id), store.hierarchyCodes());
    }

    /**
     * Returns the version of an org unit.
     *
     * @param id the ID
     * @return the number that each write of the unit counts up
     * @throws FuhitoException when the unit does not exist
     */
    public long version(String id) {
        return requireUnit(id).version();
    }

    /**
     * Returns the name of the system that made an org unit.
     *
     * @param id the ID
     * @return the name of the system, or null when no system made the unit
     * @throws FuhitoException when the realm has no such unit
     */
    public String source(String id) {
        return requireUnit(id).source();
    }

    /**
     * The values that a read of an org unit has on the dates of the details that a change of details replaces.
     *
     * @param parentIds the IDs of the parents in the hierarchy that the request names
     * @param workLocations the codes of the work locations that apply
     * @param costCenters the codes of the cost centers that apply
     */
    public record ReplacedDetails(Set<String> parentIds, Set<String> workLocations, Set<String> costCenters) {
    }

    /**
     * Returns the values that a read of an org unit has on the dates of the details that a change of details from a
     * date replaces. These dates run from that date to the end of the stored details of that date. The parent and the
     * work location and cost center that apply can change inside them, when the unit moves or a unit above changes.
     *
     * @param id the ID
     * @param validFrom the first date of the change, as {@code YYYY-MM-DD}
     * @param hierarchy the code of the hierarchy of the parents, or null for the default one
     * @return the values. A value that no date has, such as the parent of a root, is not there.
     * @throws FuhitoException when the date is bad, the hierarchy does not exist, or the unit does not exist on the
     *         date
     */
    public ReplacedDetails replacedDetails(String id, String validFrom, String hierarchy) {
        LocalDate from = Checks.date("validFrom", validFrom);
        String parentHierarchyId = hierarchyId(hierarchy);
        requireUnit(id);
        ValidPeriod replaced = new ValidPeriod(from, versionOn(id, from).period().to());
        Set<String> parentIds = store.parentLinks(parentHierarchyId, id).stream()
                .filter(link -> link.period().intersection(replaced).isPresent())
                .map(OrgUnitLink::parentId)
                .collect(Collectors.toUnmodifiableSet());
        Function<String, String> code = UnitPlaces.codes(refItems);
        Set<String> workLocations = new HashSet<>();
        Set<String> costCenters = new HashSet<>();
        for (UnitDefaults applying : UnitPlaces.applying(store, id, replaced).values()) {
            Optional.ofNullable(code.apply(applying.workLocationId())).ifPresent(workLocations::add);
            Optional.ofNullable(code.apply(applying.costCenterId())).ifPresent(costCenters::add);
        }
        return new ReplacedDetails(parentIds, Set.copyOf(workLocations), Set.copyOf(costCenters));
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
                Localized.check(details.primaryLanguage(), details.names(), (field, name) -> name.checked(field)),
                unitType == null ? null : unitType.id(),
                idOf(RefKind.WORK_LOCATION, details.workLocation(), current == null ? null : current.workLocationId()),
                idOf(RefKind.COST_CENTER, details.costCenter(), current == null ? null : current.costCenterId()),
                details.displayOrder() == null ? 0 : details.displayOrder(),
                attributeRules.check(AttributeDefinition.Target.ORG_UNIT, details.attributes()));
    }

    private String idOf(RefKind kind, String code, String currentId) {
        if (code == null) {
            return null;
        }
        return items.byCode().apply(kind, code)
                .filter(item -> item.active() || item.id().equals(currentId))
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", kind.name(), "code", code)));
    }

    // A unit may keep a unit type that was deactivated after the unit got it.
    private RefItem unitTypeForChange(String code, OrgUnitVersion current) {
        return items.byCode().apply(RefKind.UNIT_TYPE, code)
                .filter(item -> item.active() || item.id().equals(current.unitTypeId()))
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown", Map.of("unitType", code)));
    }

    private RefItem activeUnitType(String code) {
        return items.byCode().apply(RefKind.UNIT_TYPE, code)
                .filter(RefItem::active)
                .orElseThrow(() -> FuhitoException.invalid("org_unit.unit_type_unknown", Map.of("unitType", code)));
    }

    private void requireFreeCode(String code, String unitId, ValidPeriod period) {
        if (code != null) {
            requireFreeCode(store.versionsWithCodes(List.of(code)), code, unitId, period);
        }
    }

    // No two org units have the same code on the same date. One unit keeps its code over many periods.
    private static void requireFreeCode(List<OrgUnitVersion> holders, String code, String unitId,
            ValidPeriod period) {
        if (holders.stream().anyMatch(v -> !v.orgUnitId().equals(unitId) && v.period().overlaps(period))) {
            throw FuhitoException.conflict("org_unit.code_taken", Map.of("code", code));
        }
    }

    /**
     * Whether a unit is a lateral unit or a rollup unit in a period of its details.
     *
     * @param period the period of the details
     * @param lateral true for a lateral unit
     * @param rollup true for a rollup unit
     */
    record Nature(ValidPeriod period, boolean lateral, boolean rollup) {

        static Nature of(ValidPeriod period, RefItem unitType) {
            return new Nature(period, unitType != null && unitType.lateral(), unitType != null && unitType.rollup());
        }
    }

    private List<Nature> natures(String unitId) {
        return natures(store.versions(unitId));
    }

    private List<Nature> natures(List<OrgUnitVersion> versions) {
        return versions.stream()
                .map(v -> Nature.of(v.period(), v.unitTypeId() == null ? null
                        : items.byId().apply(v.unitTypeId()).orElse(null)))
                .toList();
    }

    /**
     * Requires that a link of the default hierarchy joins no rollup unit on any date, and that the parent of a line
     * unit there is a line unit on every date. A lateral unit may be anywhere.
     *
     * @param childId the ID of the child
     * @param child the natures of the child
     * @param parentId the ID of the parent
     * @param parent the natures of the parent
     * @param link the period of the link
     * @throws FuhitoException when the link breaks the rule on a date
     */
    static void requireDefaultLink(String childId, List<Nature> child, String parentId, List<Nature> parent,
            ValidPeriod link) {
        for (Nature below : child) {
            for (Nature above : parent) {
                Optional<ValidPeriod> both = link.intersection(below.period())
                        .flatMap(period -> period.intersection(above.period()));
                if (both.isEmpty()) {
                    continue;
                }
                Map<String, String> params = Map.of("id", childId, "parentId", parentId,
                        "date", both.get().from().toString());
                if (below.rollup()) {
                    throw FuhitoException.conflict("org_unit.rollup_has_parent", params);
                }
                if (above.rollup()) {
                    throw FuhitoException.conflict("org_unit.rollup_has_child", params);
                }
                if (!below.lateral() && above.lateral()) {
                    throw FuhitoException.conflict("org_unit.line_below_lateral", params);
                }
            }
        }
    }

    private void requireParentCovers(String parentId, ValidPeriod period) {
        requireParentCovers(parentId, lifeOf(store.versions(parentId)), period);
    }

    // Versions of one unit have no gaps.
    private static Optional<ValidPeriod> lifeOf(List<OrgUnitVersion> versions) {
        return versions.isEmpty() ? Optional.empty()
                : Optional.of(new ValidPeriod(versions.getFirst().period().from(), versions.getLast().period().to()));
    }

    /**
     * Requires that a parent exists on every date of a link to it.
     *
     * @param parentId the ID of the parent
     * @param parentLife the life of the parent, or empty when the realm has no such unit
     * @param period the period of the link
     * @throws FuhitoException when the parent does not exist on a date of the link
     */
    static void requireParentCovers(String parentId, Optional<ValidPeriod> parentLife, ValidPeriod period) {
        ValidPeriod life = parentLife.orElseThrow(
                () -> FuhitoException.invalid("org_unit.parent_unknown", Map.of("parentId", parentId)));
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("org_unit.parent_not_valid",
                    Map.of("parentId", parentId, "validFrom", period.from().toString()));
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}

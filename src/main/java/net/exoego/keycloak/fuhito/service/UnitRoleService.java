package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;

/**
 * Operations on the roles that employments hold over org units in one realm.
 */
public class UnitRoleService {

    /**
     * A role with the code of its reference item.
     *
     * @param role the role
     * @param code the code of the role
     */
    public record View(UnitRole role, String code) {
    }

    /**
     * A role on a date, with the names that show it.
     *
     * @param view the role with the code of its reference item
     * @param roleName the name of the role on the date, or null when the role is unknown
     * @param orgUnitNames the names of the org unit on the date, or null when the unit does not exist on the
     *        date
     */
    public record Holder(View view, String roleName, Localized<OrgUnitName> orgUnitNames) {
    }

    /**
     * The values of a list of the roles of a date across the realm. They are not checked yet. A value that is null
     * keeps everything.
     *
     * @param role the code of the role
     * @param orgUnitId the ID of the org unit that the role is over. A unit above does not count.
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     */
    public record Search(String role, String orgUnitId, String asOf) {
    }

    /**
     * The stores that the service reads besides its own.
     *
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     */
    public record Sources(EmploymentStore employments, OrgUnitStore orgUnits, RefItemStore refItems) {
    }

    private final UnitRoleStore store;
    private final Sources sources;
    private final Today today;

    /**
     * Creates the service.
     *
     * @param store the storage of roles of the realm
     * @param sources the other stores of the realm
     * @param today the rule that decides what "today" is
     */
    public UnitRoleService(UnitRoleStore store, Sources sources, Today today) {
        this.store = store;
        this.sources = sources;
        this.today = today;
    }

    /**
     * Gives an employment a role over an org unit. With no end date, the role lasts as long as the employment.
     *
     * @param employmentId the ID of the employment
     * @param orgUnitId the ID of the org unit
     * @param role the code of the role
     * @param includesDescendants true when the role also covers the units below, or null for false
     * @param validFrom the first date of the role, as {@code YYYY-MM-DD}
     * @param validTo the first date after the role, or null
     * @return the new role
     * @throws FuhitoException when a value is bad, the role is not inside the employment or the life of the org
     *         unit, or the employment holds the same role over the unit on the same dates already
     */
    public View add(String employmentId, String orgUnitId, String role, Boolean includesDescendants,
            String validFrom, String validTo) {
        Checks.required("employmentId", employmentId);
        Checks.required("orgUnitId", orgUnitId);
        Checks.required("role", role);
        LocalDate from = Checks.date("validFrom", validFrom);
        LocalDate to = Checks.optionalDate("validTo", validTo);
        Employment employment = sources.employments().find(employmentId).orElseThrow(() -> FuhitoException.invalid(
                "unit_role.employment_unknown", Map.of("employmentId", employmentId)));
        ValidPeriod period = Checks.period(from, to == null ? employment.period().to() : to);
        if (!employment.period().encloses(period)) {
            throw FuhitoException.conflict("unit_role.outside_employment",
                    Map.of("employmentId", employmentId, "validFrom", validFrom));
        }
        List<OrgUnitVersion> versions = sources.orgUnits().versions(orgUnitId);
        if (versions.isEmpty()) {
            throw FuhitoException.invalid("unit_role.org_unit_unknown", Map.of("orgUnitId", orgUnitId));
        }
        if (!new ValidPeriod(versions.getFirst().period().from(), versions.getLast().period().to()).encloses(period)) {
            throw FuhitoException.conflict("unit_role.org_unit_not_valid",
                    Map.of("orgUnitId", orgUnitId, "validFrom", validFrom));
        }
        RefItem item = sources.refItems().findByCode(RefKind.UNIT_ROLE, role)
                .filter(RefItem::active)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", RefKind.UNIT_ROLE.name(), "code", role)));
        store.findByEmployment(employmentId).stream()
                .filter(other -> other.orgUnitId().equals(orgUnitId) && other.roleId().equals(item.id())
                        && other.period().overlaps(period))
                .findFirst()
                .ifPresent(other -> {
                    throw FuhitoException.conflict("unit_role.overlap", Map.of("id", other.id()));
                });
        UnitRole added = new UnitRole(UUID.randomUUID().toString(), employmentId, employment.personId(),
                employment.userId(), orgUnitId, item.id(), Boolean.TRUE.equals(includesDescendants), period, 1);
        store.insert(added);
        return new View(added, item.code());
    }

    /**
     * Returns a role.
     *
     * @param id the ID
     * @return the role
     * @throws FuhitoException when the realm has no such role
     */
    public View get(String id) {
        return view(store.find(id)
                .orElseThrow(() -> FuhitoException.notFound("unit_role.not_found", Map.of("id", id))), new HashMap<>());
    }

    /**
     * Finds a role.
     *
     * @param id the ID
     * @return the role, or empty when the realm has no such role
     */
    public Optional<View> find(String id) {
        return store.find(id).map(role -> view(role, new HashMap<>()));
    }

    /**
     * Lists the roles of an employment, oldest first.
     *
     * @param employmentId the ID of the employment
     * @return the roles
     */
    public List<View> ofEmployment(String employmentId) {
        return views(store.findByEmployment(employmentId));
    }

    /**
     * Lists a page of the roles that hold on a date, ordered by ID.
     *
     * @param date the date
     * @param afterId the ID after which the page starts, or null for the start
     * @param max the largest number of roles
     * @return the roles
     */
    public List<View> listOn(LocalDate date, String afterId, int max) {
        return views(store.searchOn(new UnitRoleStore.Search(null, null, date), afterId, 0, max));
    }

    /**
     * Lists a page of the roles of a date across the realm that match a search, ordered by ID, with their names.
     *
     * @param search what to keep and the date
     * @param page the part of the list to return
     * @param after the cursor after which the page starts, or null
     * @return the roles of the page
     * @throws FuhitoException when a value is bad, the role is unknown, or the cursor is not of this list
     */
    public List<Holder> list(Search search, Page page, String after) {
        UnitRoleStore.Search filter = filter(search);
        String afterId = after == null ? null : Cursor.decode(Cursor.UNIT_ROLES, after).getFirst();
        List<UnitRole> roles = store.searchOn(filter, afterId, page.first(), page.max());
        Map<String, RefItem> items = new HashMap<>();
        sources.refItems().list(RefKind.UNIT_ROLE, true, filter.date()).forEach(item -> items.put(item.id(), item));
        Map<String, Localized<OrgUnitName>> unitNames = new HashMap<>();
        sources.orgUnits().placedOn(sources.orgUnits().defaultHierarchyId(),
                        roles.stream().map(UnitRole::orgUnitId).distinct().toList(), filter.date())
                .forEach(placed -> unitNames.put(placed.unit().id(), placed.version().names()));
        return roles.stream().map(role -> {
            Optional<RefItem> item = Optional.ofNullable(items.get(role.roleId()));
            return new Holder(new View(role, item.map(RefItem::code).orElse(null)),
                    item.map(RefItem::name).orElse(null), unitNames.get(role.orgUnitId()));
        }).toList();
    }

    /**
     * Counts the roles of a date across the realm that match a search.
     *
     * @param search what to keep and the date
     * @return the number of roles
     * @throws FuhitoException when a value is bad or the role is unknown
     */
    public long count(Search search) {
        return store.countSearchOn(filter(search));
    }

    private UnitRoleStore.Search filter(Search search) {
        return new UnitRoleStore.Search(roleId(search.role()), search.orgUnitId(), dateOfRealm(search.asOf()));
    }

    private LocalDate dateOfRealm(String asOf) {
        return asOf == null ? today.ofRealm() : Checks.optionalDate("asOf", asOf);
    }

    // A filter may name a role that is no longer active, because old rows may still use it.
    private String roleId(String role) {
        return role == null ? null : sources.refItems().findByCode(RefKind.UNIT_ROLE, role)
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", RefKind.UNIT_ROLE.name(), "code", role)));
    }

    /**
     * Ends a role earlier.
     *
     * @param id the ID
     * @param validTo the first date on which the role no longer holds, as {@code YYYY-MM-DD}
     * @return the changed role
     * @throws FuhitoException when the role does not exist or the date is not inside it
     */
    public View end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        UnitRole role = get(id).role();
        if (!to.isAfter(role.period().from()) || (role.period().to() != null && to.isAfter(role.period().to()))) {
            throw FuhitoException.conflict("unit_role.end_outside",
                    Map.of("validFrom", role.period().from().toString(), "validTo", validTo));
        }
        UnitRole changed = role.withPeriod(role.period().endingAt(to));
        store.update(changed);
        return get(id);
    }

    /**
     * Deletes a role that was stored by mistake. Its entries in the change log stay.
     *
     * @param id the ID
     * @throws FuhitoException when the role does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    /**
     * Lists a page of the roles over an org unit on a date. These are the roles over the unit, and the roles over a
     * unit above in the default hierarchy that cover the units below. The nearest unit comes first.
     *
     * @param orgUnitId the ID of the org unit
     * @param role the code of the role to keep, or null for every role
     * @param nearest true to keep, for each role, only the holders of the nearest unit
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the org unit
     * @param page the part of the list to return
     * @return the roles of the page
     * @throws FuhitoException when the org unit does not exist, the role is unknown, or the date is bad
     */
    public List<View> holders(String orgUnitId, String role, boolean nearest, String asOf, Page page) {
        OverUnit read = overUnit(orgUnitId, role, asOf);
        return views(store.holdersOn(sources.orgUnits().defaultHierarchyId(), orgUnitId, read.roleId(), nearest,
                read.date(), page.first(), page.max()));
    }

    /**
     * Counts the roles that {@link #holders} lists on all pages together.
     *
     * @param orgUnitId the ID of the org unit
     * @param role the code of the role to keep, or null for every role
     * @param nearest true to keep, for each role, only the holders of the nearest unit
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the org unit
     * @return the number of roles
     * @throws FuhitoException when the org unit does not exist, the role is unknown, or the date is bad
     */
    public long countHolders(String orgUnitId, String role, boolean nearest, String asOf) {
        OverUnit read = overUnit(orgUnitId, role, asOf);
        return store.countHoldersOn(sources.orgUnits().defaultHierarchyId(), orgUnitId, read.roleId(), nearest,
                read.date());
    }

    private record OverUnit(String roleId, LocalDate date) {
    }

    private OverUnit overUnit(String orgUnitId, String role, String asOf) {
        if (sources.orgUnits().findUnit(orgUnitId).isEmpty()) {
            throw FuhitoException.notFound("org_unit.not_found", Map.of("id", orgUnitId));
        }
        String roleId = roleId(role);
        return new OverUnit(roleId, asOf == null ? today.ofUnit(orgUnitId) : Checks.optionalDate("asOf", asOf));
    }

    /**
     * Lists a page of the roles of a person on a date.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     * @param page the part of the list to return
     * @return the roles of the page, in the order of their start
     * @throws FuhitoException when the date is bad
     */
    public List<View> ofPerson(String personId, String asOf, Page page) {
        return views(store.ofPersonOn(personId, dateOfRealm(asOf), page.first(), page.max()));
    }

    /**
     * Counts the roles that {@link #ofPerson} lists on all pages together.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     * @return the number of roles
     * @throws FuhitoException when the date is bad
     */
    public long countOfPerson(String personId, String asOf) {
        return store.countOfPersonOn(personId, dateOfRealm(asOf));
    }

    private List<View> views(List<UnitRole> roles) {
        Map<String, String> codes = new HashMap<>();
        return roles.stream().map(role -> view(role, codes)).toList();
    }

    private View view(UnitRole role, Map<String, String> codes) {
        return new View(role, codes.computeIfAbsent(role.roleId(), id -> Optional.ofNullable(id)
                .flatMap(sources.refItems()::find).map(RefItem::code).orElse(null)));
    }
}

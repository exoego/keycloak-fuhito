package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.OrgUnitService.Nature;

import static net.exoego.keycloak.fuhito.service.RestoreRules.all;
import static net.exoego.keycloak.fuhito.service.RestoreRules.broken;
import static net.exoego.keycloak.fuhito.service.RestoreRules.check;
import static net.exoego.keycloak.fuhito.service.RestoreRules.outside;
import static net.exoego.keycloak.fuhito.service.RestoreRules.period;
import static net.exoego.keycloak.fuhito.service.RestoreRules.requireNoGaps;

/**
 * The org units of an export, read once, and the rules between their rows: the versions of a unit, its parents, and
 * the units that follow it. Each rule is the one that the writes of org units use.
 */
final class RestoreUnits {

    private final List<RealmExport.Unit> units;
    private final Map<String, RealmExport.Item> items = new HashMap<>();
    private final Map<String, List<RealmExport.Version>> versions = new HashMap<>();
    private final Map<String, List<Nature>> natures = new HashMap<>();
    private final Map<String, Map<String, List<OrgUnitLink>>> links = new HashMap<>();

    RestoreUnits(RealmExport document) {
        units = all(document.orgUnits());
        all(document.refItems()).forEach(item -> items.put(item.id(), item));
        for (RealmExport.Unit unit : units) {
            List<RealmExport.Version> sorted = new ArrayList<>(all(unit.versions()));
            sorted.sort(Comparator.comparing(v -> LocalDate.parse(v.validFrom())));
            versions.put(unit.id(), sorted);
            for (RealmExport.Parent parent : all(unit.parents())) {
                links.computeIfAbsent(parent.hierarchy(), code -> new HashMap<>())
                        .computeIfAbsent(unit.id(), id -> new ArrayList<>())
                        .add(new OrgUnitLink(parent.id(), parent.hierarchy(), unit.id(), parent.parentId(),
                                period(parent.validFrom(), parent.validTo())));
            }
        }
    }

    /**
     * Returns the life of a unit, from the start of its first version to the end of its last, as a write takes it.
     *
     * @param unitId the ID of the unit
     * @return the life
     */
    ValidPeriod life(String unitId) {
        List<RealmExport.Version> sorted = versions.get(unitId);
        return period(sorted.getFirst().validFrom(), sorted.getLast().validTo());
    }

    /**
     * Returns the version of a unit on a date.
     *
     * @param unitId the ID of the unit
     * @param date the date
     * @return the version, or empty when the unit does not exist on the date
     */
    Optional<RealmExport.Version> versionOn(String unitId, LocalDate date) {
        return versions.get(unitId).stream().filter(v -> period(v.validFrom(), v.validTo()).contains(date))
                .findFirst();
    }

    /**
     * Tells whether a unit type is marked as a legal entity.
     *
     * @param unitTypeId the ID of the unit type, or null
     * @return true when it is
     */
    boolean legalEntity(String unitTypeId) {
        return unitTypeId != null && items.get(unitTypeId).legalEntity();
    }

    /**
     * Requires that every unit has versions with no gaps, that each parent link keeps the rules of the tree, and that
     * a unit that others follow ends on the date of the succession.
     */
    void require() {
        for (RealmExport.Unit unit : units) {
            List<RealmExport.Version> sorted = versions.get(unit.id());
            if (sorted.isEmpty()) {
                throw broken("org-units", unit.id(), "versions", null);
            }
            requireNoGaps("org-unit-versions", sorted, RealmExport.Version::id,
                    v -> period(v.validFrom(), v.validTo()));
        }
        units.forEach(this::requireParents);
        units.forEach(this::requireSuccessors);
    }

    // A link lies inside the life of its unit. The other rules come in the order of a move.
    private void requireParents(RealmExport.Unit unit) {
        ValidPeriod life = life(unit.id());
        for (RealmExport.Parent parent : all(unit.parents())) {
            ValidPeriod link = period(parent.validFrom(), parent.validTo());
            if (!life.encloses(link)) {
                throw broken("org-unit-parents", parent.id(), outside(link, life), null);
            }
            ValidPeriod parentLife = life(parent.parentId());
            check("org-unit-parents", parent.id(), outside(link, parentLife),
                    () -> OrgUnitService.requireParentCovers(parent.parentId(), Optional.of(parentLife), link));
            Map<String, List<OrgUnitLink>> ofHierarchy = links.get(parent.hierarchy());
            check("org-unit-parents", parent.id(), "parentId", () -> OrgUnitService.requireNoCycle(
                    id -> ofHierarchy.getOrDefault(id, List.of()), unit.id(), parent.parentId(), link,
                    new HashSet<>()));
            if (Hierarchy.DEFAULT_CODE.equals(parent.hierarchy())) {
                check("org-unit-parents", parent.id(), "parentId", () -> OrgUnitService.requireDefaultLink(
                        unit.id(), natures(unit.id()), parent.parentId(), natures(parent.parentId()), link));
            }
        }
    }

    // A succession ends the unit on its date, and that end never moves. The unit that follows exists on the date.
    private void requireSuccessors(RealmExport.Unit unit) {
        ValidPeriod life = life(unit.id());
        for (RealmExport.Succession succession : all(unit.successors())) {
            LocalDate date = LocalDate.parse(succession.effectiveDate());
            if (life.isOpenEnded()) {
                throw broken("org-unit-versions", versions.get(unit.id()).getLast().id(), "validTo",
                        "org_unit.has_successors");
            }
            if (!life.to().equals(date)) {
                throw broken("org-unit-successions", succession.id(), "effectiveDate", "org_unit.has_successors");
            }
            check("org-unit-successions", succession.id(), "effectiveDate", () -> OrgUnitService.requireFollows(
                    succession.successorId(), life(succession.successorId()), date));
        }
    }

    private List<Nature> natures(String unitId) {
        return natures.computeIfAbsent(unitId, id -> versions.get(id).stream().map(v -> {
            RealmExport.Item type = v.unitTypeId() == null ? null : items.get(v.unitTypeId());
            return new Nature(period(v.validFrom(), v.validTo()), type != null && type.lateral(),
                    type != null && type.rollup());
        }).toList());
    }
}

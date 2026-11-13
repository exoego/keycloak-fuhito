package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Decides today for the realm and for each org unit, from the settings of the realm and of the org units.
 */
public class UnitDays implements Today {

    private final OrgUnitStore store;
    private final BusinessDay.Rule realmRule;
    private final Clock base;

    private Map<String, OrgUnit> units;
    private Map<String, String> parents;
    private final Map<String, LocalDate> cache = new HashMap<>();

    /**
     * Creates the rule for one request.
     *
     * @param store the storage of org units of the realm
     * @param realmRule the time zone and the day start of the realm
     * @param base the clock that gives the real time
     */
    public UnitDays(OrgUnitStore store, BusinessDay.Rule realmRule, Clock base) {
        this.store = store;
        this.realmRule = realmRule;
        this.base = base;
    }

    @Override
    public LocalDate ofRealm() {
        return LocalDate.now(realmRule.clock(base));
    }

    @Override
    public LocalDate ofUnit(String orgUnitId) {
        load();
        return cache.computeIfAbsent(orgUnitId, this::resolve);
    }

    @Override
    public Set<LocalDate> everywhere() {
        load();
        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.add(ofRealm());
        units.values().stream()
                .filter(unit -> unit.timeZone() != null || unit.dayStart() != null)
                .forEach(unit -> dates.add(ofUnit(unit.id())));
        return dates;
    }

    // The tree can change on a date, and the date depends on the tree. The tree of the date of the realm is
    // used to find the units above.
    private void load() {
        if (units != null) {
            return;
        }
        units = new HashMap<>();
        store.units().forEach(unit -> units.put(unit.id(), unit));
        parents = new HashMap<>();
        if (units.values().stream().anyMatch(unit -> unit.timeZone() != null || unit.dayStart() != null)) {
            for (OrgUnitLink link : store.linksOn(store.defaultHierarchyId(), ofRealm())) {
                parents.put(link.childId(), link.parentId());
            }
        }
    }

    // The nearest time zone and the nearest day start are looked up one by one, so a unit can set only one.
    private LocalDate resolve(String orgUnitId) {
        String timeZone = null;
        String dayStart = null;
        Set<String> seen = new HashSet<>();
        for (String id = orgUnitId; id != null && seen.add(id); id = parents.get(id)) {
            OrgUnit unit = units.get(id);
            if (unit == null) {
                break;
            }
            if (timeZone == null) {
                timeZone = unit.timeZone();
            }
            if (dayStart == null) {
                dayStart = unit.dayStart();
            }
        }
        return LocalDate.now(realmRule.with(timeZone, dayStart).clock(base));
    }
}

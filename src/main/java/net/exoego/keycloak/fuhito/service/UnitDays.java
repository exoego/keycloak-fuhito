package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Decides today for the realm and for each org unit, from the settings of the realm and of the org units.
 */
public class UnitDays implements Today {

    private final OrgUnitStore store;
    private final BusinessDay.Rule realmRule;
    private final Clock base;

    // Only the units that set a time of their own. Most realms have none, and then nothing else is read.
    private Map<String, OrgUnit> ownTime;
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
        if (ownTime().isEmpty()) {
            return ofRealm();
        }
        return cache.computeIfAbsent(orgUnitId, this::resolve);
    }

    @Override
    public Set<LocalDate> everywhere() {
        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.add(ofRealm());
        ownTime().keySet().forEach(id -> dates.add(ofUnit(id)));
        return dates;
    }

    /**
     * Returns the rule of the realm and the rule of each org unit that sets a time of its own.
     * The units below such a unit follow one of these rules too.
     *
     * @return the rules, with no rule twice
     */
    public Set<BusinessDay.Rule> rules() {
        Set<BusinessDay.Rule> rules = new LinkedHashSet<>();
        rules.add(realmRule);
        ownTime().keySet().forEach(id -> rules.add(ruleOf(id)));
        return rules;
    }

    private Map<String, OrgUnit> ownTime() {
        if (ownTime == null) {
            ownTime = new HashMap<>();
            store.unitsWithOwnTime().forEach(unit -> ownTime.put(unit.id(), unit));
        }
        return ownTime;
    }

    private LocalDate resolve(String orgUnitId) {
        return LocalDate.now(ruleOf(orgUnitId).clock(base));
    }

    // The tree can change on a date, and the date depends on the tree. The tree of the date of the realm is
    // used to find the units above.
    // The nearest time zone and the nearest day start are looked up one by one, so a unit can set only one.
    private BusinessDay.Rule ruleOf(String orgUnitId) {
        String timeZone = null;
        String dayStart = null;
        for (String id : store.lineOn(store.defaultHierarchyId(), orgUnitId, ofRealm())) {
            OrgUnit unit = ownTime.get(id);
            if (unit == null) {
                continue;
            }
            if (timeZone == null) {
                timeZone = unit.timeZone();
            }
            if (dayStart == null) {
                dayStart = unit.dayStart();
            }
        }
        return realmRule.with(timeZone, dayStart);
    }
}

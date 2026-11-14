package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

/**
 * What today is. The realm decides it, and an org unit can decide it for itself and the units below it.
 */
public interface Today {

    /**
     * Returns the date of the realm.
     *
     * @return today for the realm
     */
    LocalDate ofRealm();

    /**
     * Returns the date of an org unit. It follows the nearest setting at the unit or above it, or the realm.
     *
     * @param orgUnitId the ID of the org unit
     * @return today for the org unit
     */
    LocalDate ofUnit(String orgUnitId);

    /**
     * Returns every date that is today somewhere in the realm.
     *
     * @return the date of the realm and the dates of the org units with their own settings
     */
    Set<LocalDate> everywhere();

    /**
     * Makes a rule where every org unit is on the date of one clock.
     *
     * @param clock the clock whose date is the date of the realm
     * @return the rule
     */
    static Today of(Clock clock) {
        return new OneClock(clock);
    }

    /**
     * A rule where every org unit is on the date of one clock.
     *
     * @param clock the clock whose date is the date of the realm
     */
    record OneClock(Clock clock) implements Today {

        @Override
        public LocalDate ofRealm() {
            return LocalDate.now(clock);
        }

        @Override
        public LocalDate ofUnit(String orgUnitId) {
            return ofRealm();
        }

        @Override
        public Set<LocalDate> everywhere() {
            return Set.of(ofRealm());
        }
    }
}

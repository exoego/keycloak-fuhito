package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneOffset;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.jboss.logging.Logger;
import org.keycloak.models.RealmModel;

/**
 * Reads the time zone and the day start of a realm from its attributes.
 */
final class RealmTime {

    /**
     * The realm attribute with the time zone, for example "Asia/Tokyo". A realm must set it.
     */
    static final String TIME_ZONE_ATTRIBUTE = "fuhito.timeZone";

    /**
     * The realm attribute with the time of day when the date changes, for example "04:00". The default is 00:00.
     */
    static final String DAY_START_ATTRIBUTE = "fuhito.dayStart";

    private static final Logger LOG = Logger.getLogger(RealmTime.class);

    private RealmTime() {
    }

    static final BusinessDay.Rule UTC = new BusinessDay.Rule(ZoneOffset.UTC, LocalTime.MIDNIGHT);

    /**
     * Returns the time zone and the day start of the realm.
     *
     * @throws FuhitoException when the realm has no time zone, or a setting is bad
     */
    static BusinessDay.Rule rule(RealmModel realm) {
        return BusinessDay.rule(realm.getAttribute(TIME_ZONE_ATTRIBUTE), realm.getAttribute(DAY_START_ATTRIBUTE));
    }

    // A login and the removal of a user must not fail because of a setting.
    // UTC is used then. It is a fixed zone, not the zone of the server.
    static BusinessDay.Rule ruleOrUtc(RealmModel realm) {
        try {
            return rule(realm);
        } catch (FuhitoException e) {
            LOG.warnf("Realm %s has no usable time zone (%s). UTC is used to decide what today is.",
                    realm.getName(), e.code());
            return UTC;
        }
    }

    static Clock clockOrUtc(RealmModel realm) {
        return ruleOrUtc(realm).clock(Clock.systemUTC());
    }
}

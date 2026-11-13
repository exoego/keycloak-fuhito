package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The real time is 01:00 on April 1 in Tokyo. It is 12:00 on March 31 in New York.
 */
class UnitDaysTest {

    private static final Clock BASE = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC);
    private static final BusinessDay.Rule TOKYO = BusinessDay.rule("Asia/Tokyo", null);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);
    private static final LocalDate MARCH_30 = LocalDate.of(2026, 3, 30);

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final OrgUnitService orgUnits = new OrgUnitService(store, new MemoryAssignmentStore(),
            new MemoryRefItemStore(), Today.of(BASE));

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2020-04-01", null,
                null, null, null)).unit().id();
    }

    private UnitDays days() {
        return new UnitDays(store, TOKYO, BASE);
    }

    @Test
    void withNoSettingsEveryUnitIsOnTheDateOfTheRealm() {
        String root = unit("Corporation", null);
        String child = unit("Office", root);

        assertEquals(APRIL_1, days().ofRealm());
        assertEquals(APRIL_1, days().ofUnit(root));
        assertEquals(APRIL_1, days().ofUnit(child));
        assertEquals(APRIL_1, days().ofUnit("no-such-unit"));
        assertEquals(Set.of(APRIL_1), days().everywhere());
    }

    @Test
    void theNearestSettingWinsAndEachSettingIsLookedUpOnItsOwn() {
        String japan = unit("Japan", null);
        String america = unit("America", null);
        String sales = unit("Sales", america);
        String team = unit("Team", sales);
        String desk = unit("Desk", team);
        orgUnits.setTime(america, "America/New_York", null);
        // After 13:00 the date changes, so 12:00 on March 31 in New York is still March 30.
        orgUnits.setTime(sales, null, "13:00");
        // 01:00 on April 1 in Tokyo is March 31 with the day start of 13:00 from above.
        orgUnits.setTime(team, " Asia/Tokyo ", " ");

        UnitDays days = days();

        assertEquals(APRIL_1, days.ofRealm());
        assertEquals(APRIL_1, days.ofUnit(japan));
        assertEquals(MARCH_31, days.ofUnit(america));
        assertEquals(MARCH_30, days.ofUnit(sales));
        assertEquals(MARCH_31, days.ofUnit(team));
        assertEquals(MARCH_31, days.ofUnit(desk));
        assertEquals(Set.of(APRIL_1, MARCH_31, MARCH_30), days.everywhere());
        assertEquals("Asia/Tokyo", store.units.get(team).timeZone());
        assertNull(store.units.get(team).dayStart());
    }

    @Test
    void aUnitFollowsTheParentItHasToday() {
        String america = unit("America", null);
        String japan = unit("Japan", null);
        String office = unit("Office", america);
        orgUnits.setTime(america, "America/New_York", null);
        assertEquals(MARCH_31, days().ofUnit(office));

        // The move starts on April 1, which is today for the realm.
        orgUnits.move(office, japan, "2026-04-01");

        assertEquals(APRIL_1, days().ofUnit(office));
    }

    @Test
    void clearingTheSettingsPutsTheUnitBackOnTheDateFromAbove() {
        String america = unit("America", null);
        orgUnits.setTime(america, "America/New_York", "13:00");
        assertEquals(MARCH_30, days().ofUnit(america));

        orgUnits.setTime(america, null, null);

        assertEquals(APRIL_1, days().ofUnit(america));
        assertEquals(Set.of(APRIL_1), days().everywhere());
    }

    @Test
    void rejectsBadSettingsAndKeepsTheOldOnes() {
        String america = unit("America", null);
        orgUnits.setTime(america, "America/New_York", "04:00");

        FuhitoException zone = assertThrows(FuhitoException.class, () -> orgUnits.setTime(america, "New York", null));
        assertEquals(FuhitoException.Reason.INVALID, zone.reason());
        assertEquals("org_unit.time_zone_invalid", zone.code());
        FuhitoException start = assertThrows(FuhitoException.class, () -> orgUnits.setTime(america, null, "4am"));
        assertEquals(FuhitoException.Reason.INVALID, start.reason());
        assertEquals("org_unit.day_start_invalid", start.code());
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> orgUnits.setTime("nope", null, null)).code());
        assertEquals("America/New_York", store.units.get(america).timeZone());
        assertEquals("04:00", store.units.get(america).dayStart());
    }

    @Test
    void aReadWithNoDateUsesTheDateOfTheUnit() {
        String america = unit("America", null);
        orgUnits.setTime(america, "America/New_York", null);
        String opensInApril = orgUnits.create(new NewOrgUnit(null, "New Office", null, null, null, null, null,
                america, "2026-04-01", null, null, null, null)).unit().id();
        OrgUnitService reads = new OrgUnitService(store, new MemoryAssignmentStore(), new MemoryRefItemStore(),
                days());

        // The new office opens on April 1. America is still on March 31, and the realm is on April 1.
        assertEquals(1, reads.subtree(america, null).size());
        assertEquals(0, reads.children(america, null).size());
        assertEquals(2, reads.subtree(america, "2026-04-01").size());
        assertEquals(2, reads.list(null, false).size());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> reads.get(opensInApril, null)).code());
    }
}

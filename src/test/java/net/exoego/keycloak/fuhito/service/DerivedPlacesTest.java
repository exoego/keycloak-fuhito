package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedPlacesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignments = new MemoryAssignmentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryPlaceStore places = new MemoryPlaceStore(store, assignments);
    private final OrgUnitService service = new OrgUnitService(store, new MemoryExternalIdStore(), assignments,
            refItems, Today.of(CLOCK), AttributeRules.NONE, places);

    // A hospital in Tokyo with a ward and a clinic in Osaka. A nurse is in the ward, and a doctor in the clinic.
    private final String hospital;
    private final String ward;
    private final String clinic;

    DerivedPlacesTest() {
        for (String code : List.of("tokyo", "osaka", "kyoto")) {
            refItems.insert(new RefItem("WORK_LOCATION-" + code, RefKind.WORK_LOCATION, code, ItemNames.of(code), 0,
                    Map.of(), true));
        }
        hospital = unit("Hospital", null, "tokyo");
        ward = unit("Ward", hospital, null);
        clinic = unit("Clinic", hospital, "osaka");
        assign("nurse", ward, null);
        assign("remote", ward, "WORK_LOCATION-kyoto");
        assign("doctor", clinic, null);
    }

    private String unit(String name, String parentId, String workLocation) {
        return service.create(new NewOrgUnit(null, "ja", ja(name), null, workLocation, null, parentId, "2026-04-01",
                null, null, null, null)).unit().id();
    }

    private void assign(String id, String orgUnitId, String workLocationId) {
        assignments.rows.add(new Assignment(id + "-row", id, id + "-employment", orgUnitId,
                ValidPeriod.startingAt(LocalDate.parse("2026-04-01")), null, null, null, workLocationId, null, false,
                true, null, 0, Map.of(), null, 1));
    }

    private static OrgUnitChange details(String name, String workLocation, String validFrom) {
        return new OrgUnitChange(validFrom, null, "ja", ja(name), null, workLocation, null, null, null);
    }

    @Test
    void aNewWorkLocationCountsUpTheUnitsBelowAndTheAssignmentsThatTakeIt() {
        long version = store.units.get(ward).version();

        List<PropagatedChange> propagated = service.changeDetails(hospital, details("Hospital", "kyoto",
                "2026-05-01")).propagated();

        assertEquals(List.of(new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, ward),
                new PropagatedChange(DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION, "nurse")), propagated);
        assertEquals(List.of(ward), places.countedUnits);
        assertEquals(List.of("nurse"), places.countedAssignments);
        assertEquals(version + 1, store.units.get(ward).version());
    }

    @Test
    void aUnitWithBothValuesOfItsOwnReadsNothingAboveIt() {
        refItems.insert(new RefItem("COST_CENTER-cc", RefKind.COST_CENTER, "cc", ItemNames.of("cc"), 0, Map.of(),
                true));
        String full = service.create(new NewOrgUnit(null, "ja", ja("Full"), null, "tokyo", "cc", hospital,
                "2026-04-01", null, null, null, null)).unit().id();
        assign("full", full, null);
        int reads = store.defaultsReads;

        List<PropagatedChange> propagated = service.changeDetails(full, new OrgUnitChange("2026-05-01", null, "ja",
                ja("Full"), null, "osaka", "cc", null, null)).propagated();

        assertEquals(List.of(new PropagatedChange(DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION, "full")),
                propagated);
        // The read of the unit in the answer is the only one.
        assertEquals(reads + 1, store.defaultsReads);
    }

    @Test
    void aChangeThatKeepsTheWorkLocationAndTheCostCenterReadsNothingBelow() {
        store.hierarchyCodes.put("project-id", "project");
        assertEquals(List.of(), service.changeDetails(hospital, details("General hospital", "tokyo", "2026-05-01"))
                .propagated());
        assertEquals(List.of(), service.move(clinic, null, "2026-05-01", "project").propagated());
        assertEquals(List.of(), places.countedUnits);
    }

    @Test
    void aMoveChangesTheMovedUnitAndTheReadsBelowIt() {
        String annex = unit("Annex", clinic, null);
        assign("clerk", annex, null);

        List<PropagatedChange> propagated = service.move(annex, ward, "2026-05-01").propagated();

        assertEquals(List.of(new PropagatedChange(DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION, "clerk")),
                propagated);
    }

    @Test
    void aWriteGivesTheUnitsThatTheRequestWritesItselfNoPropagatedChange() {
        List<PropagatedChange> propagated = service.writing(Set.of(ward))
                .changeDetails(hospital, details("Hospital", "kyoto", "2026-04-01")).propagated();

        assertEquals(List.of(new PropagatedChange(DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION, "nurse")),
                propagated);
    }

    @Test
    void aReorganizationGivesTheStepThatChangedAReadItsPropagatedChange() {
        List<ReorganizationService.Result> results = new ReorganizationService(service).apply("2026-05-01",
                List.of(new ReorganizationStep("change", null, hospital, null, service.version(hospital), null, null,
                        null, "ja", ja("Hospital"), null, "kyoto", null, null, null, null, null)), ids -> { });

        assertEquals(List.of(new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, ward),
                new PropagatedChange(DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION, "nurse")),
                results.getFirst().propagated());
        assertTrue(ReorganizationService.changesOtherReads(List.of(new ReorganizationStep("move", null, ward, null,
                1L, null, null, null, null, null, null, null, null, null, null, null, null))));
        assertFalse(ReorganizationService.changesOtherReads(List.of(new ReorganizationStep("end", null, ward, null,
                1L, null, null, null, null, null, null, null, null, null, null, null, null))));
        assertFalse(ReorganizationService.changesOtherReads(null));
    }

    @Test
    void aChangeOfDetailsCanChangeOtherReadsWhenItGivesAnotherWorkLocationOrCostCenter() {
        assertFalse(service.changesPlaces(hospital, "2026-05-01", "tokyo", null));
        assertTrue(service.changesPlaces(hospital, "2026-05-01", "kyoto", null));
        assertTrue(service.changesPlaces(hospital, "2026-05-01", "tokyo", "cc"));
        assertTrue(service.changesPlaces(hospital, "2020-01-01", "tokyo", null));
        assertTrue(OrgUnitService.movesPlaces(null));
        assertTrue(OrgUnitService.movesPlaces("default"));
        assertFalse(OrgUnitService.movesPlaces("project"));
    }

    @Test
    void anAssignmentInAnotherTimeZoneReadsTheDateOfItsUnit() {
        // It is still June 30 for the ward when July 1 starts in the realm.
        LocalDate july = LocalDate.parse("2026-07-01");
        Today twoDays = new Today() {
            @Override
            public LocalDate ofRealm() {
                return july;
            }

            @Override
            public LocalDate ofUnit(String orgUnitId) {
                return orgUnitId.equals(ward) ? july.minusDays(1) : july;
            }

            @Override
            public Set<LocalDate> everywhere() {
                return Set.of(july, july.minusDays(1));
            }
        };
        OrgUnitService zoned = new OrgUnitService(store, new MemoryExternalIdStore(), assignments, refItems, twoDays,
                AttributeRules.NONE, places);

        List<PropagatedChange> propagated = zoned.changeDetails(hospital, details("Hospital", "kyoto",
                "2026-07-01")).propagated();

        assertEquals(List.of(new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, ward)), propagated);
    }
}

package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReorganizationServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final OrgUnitService orgUnits = new OrgUnitService(store, externalIds, new MemoryAssignmentStore(), refItems,
            Clock.systemUTC());
    private final ReorganizationService service = new ReorganizationService(orgUnits);
    private final List<Collection<String>> locked = new ArrayList<>();

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, "ja", ja(name), null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    // A step of a stored unit names the version that the unit has now, as a caller that just read it does.
    private Long version(String id) {
        return id == null ? null : orgUnits.version(id);
    }

    private static ReorganizationStep create(String ref, String name, String parentId, String parentRef) {
        return new ReorganizationStep("create", ref, null, null, null, parentId, parentRef, null, "ja", ja(name), null, null, null, null, null, null, null);
    }

    private ReorganizationStep change(String id, String name) {
        return new ReorganizationStep("change", null, id, null, version(id), null, null, null, "ja", ja(name), null, null, null, null, null, null, null);
    }

    private ReorganizationStep move(String id, String unitRef, String parentId, String parentRef) {
        return new ReorganizationStep("move", null, id, unitRef, version(id), parentId, parentRef, null, "ja", ja(null), null, null, null, null, null, null, null);
    }

    private ReorganizationStep end(String id) {
        return new ReorganizationStep("end", null, id, null, version(id), null, null, null, "ja", ja(null), null, null, null, null, null, null, null);
    }

    private ReorganizationStep succeed(String id, List<String> successorIds, List<String> successorRefs) {
        return new ReorganizationStep("succeed", null, id, null, version(id), null, null, null, "ja", ja(null), null, null, null, null, null, successorIds, successorRefs);
    }

    private List<ReorganizationService.Result> apply(String date, List<ReorganizationStep> steps) {
        return service.apply(date, steps, locked::add);
    }

    private List<String> predecessorsOf(String id) {
        return orgUnits.successions(id).predecessors().stream().map(OrgUnitSuccession::predecessorId).toList();
    }

    private List<String> successorsOf(String id) {
        return orgUnits.successions(id).successors().stream().map(OrgUnitSuccession::successorId).toList();
    }

    private List<String> tree(String asOf) {
        return Trees.all(orgUnits, asOf, null).stream()
                .map(s -> s.version().names().primary().name() + (s.parentId() == null ? "" : " < "
                        + orgUnits.get(s.parentId(), asOf).version().names().primary().name()))
                .toList();
    }

    private FuhitoException failure(String date, List<ReorganizationStep> steps) {
        return assertThrows(FuhitoException.class, () -> apply(date, steps));
    }

    @Test
    void appliesEveryKindOfStepOnOneDate() {
        String hospital = unit("Hospital", null);
        String medicine = unit("Medicine", hospital);
        String surgery = unit("Surgery", hospital);
        String oldOffice = unit("Old Office", hospital);

        List<ReorganizationService.Result> results = apply("2027-04-01", List.of(
                change(hospital, "General Hospital"),
                create("clinical", "Clinical Division", hospital, null),
                move(medicine, null, null, "clinical"),
                move(surgery, null, null, "clinical"),
                create(null, "Outpatient", null, "clinical"),
                end(oldOffice)));

        assertEquals(List.of("change", "create", "move", "move", "create", "end"),
                results.stream().map(ReorganizationService.Result::op).toList());
        // Every stored unit of the steps is locked at once. A unit that a step creates needs no lock.
        assertEquals(List.of(List.of(hospital, medicine, surgery, oldOffice)), locked);
        assertEquals("General Hospital", results.getFirst().unit().version().names().primary().name());
        assertEquals(results.get(1).unit().unit().id(), results.get(2).unit().parentId());
        assertEquals("Old Office", results.get(5).unit().version().names().primary().name());
        assertEquals(List.of("Hospital", "Medicine < Hospital", "Old Office < Hospital", "Surgery < Hospital"),
                tree("2027-03-31"));
        assertEquals(List.of("General Hospital", "Clinical Division < General Hospital",
                "Medicine < Clinical Division", "Outpatient < Clinical Division", "Surgery < Clinical Division"),
                tree("2027-04-01"));
    }

    @Test
    void aStepGivesAUnitItsWorkLocationAndItsCostCenter() {
        refItems.insert(new RefItem("tokyo", RefKind.WORK_LOCATION, "tokyo", "Tokyo", Map.of(), 0, Map.of(), true));
        refItems.insert(new RefItem("cc", RefKind.COST_CENTER, "cc", "CC", Map.of(), 0, Map.of(), true));
        String hospital = unit("Hospital", null);

        List<ReorganizationService.Result> results = apply("2027-04-01", List.of(
                new ReorganizationStep("change", null, hospital, null, version(hospital), null, null, null,
                        "ja", ja("Hospital"), null, "tokyo", null, null, null, null, null),
                new ReorganizationStep("create", null, null, null, null, hospital, null, null, "ja", ja("Ward"), null, null, "cc", null, null, null, null)));

        LocationAndCost ward = results.get(1).unit().places();
        assertEquals("tokyo", results.getFirst().unit().places().workLocation());
        assertNull(ward.workLocation());
        assertEquals("cc", ward.costCenter());
        assertEquals("tokyo", ward.effectiveWorkLocation());
        assertNull(orgUnits.get(hospital, "2027-03-31").places().effectiveWorkLocation());
    }

    @Test
    void aMergerAndASplitLinkTheOldUnitsToTheNewOnes() {
        String hospital = unit("Hospital", null);
        String a = unit("Ward A", hospital);
        String b = unit("Ward B", hospital);
        String c = unit("Ward C", hospital);
        String office = unit("Old Office", hospital);

        List<ReorganizationService.Result> results = apply("2027-04-01", List.of(
                create("ab", "Ward AB", hospital, null),
                succeed(a, null, List.of("ab")),
                succeed(b, null, List.of("ab")),
                create("c1", "Ward C1", hospital, null),
                create("c2", "Ward C2", hospital, null),
                succeed(c, null, List.of("c1", "c2")),
                // A unit can also follow one that exists already.
                succeed(office, List.of(hospital), null)));

        String ab = results.getFirst().unit().unit().id();
        String c1 = results.get(3).unit().unit().id();
        String c2 = results.get(4).unit().unit().id();
        assertEquals("Ward A", results.get(1).unit().version().names().primary().name());
        assertEquals(List.of(ab), results.get(1).successions().successors().stream()
                .map(OrgUnitSuccession::successorId).toList());
        assertNull(results.getFirst().successions());
        assertEquals(List.of("Hospital", "Ward AB < Hospital", "Ward C1 < Hospital", "Ward C2 < Hospital"),
                tree("2027-04-01"));
        assertEquals(List.of(a, b).stream().sorted().toList(), predecessorsOf(ab));
        assertEquals(List.of(), successorsOf(ab));
        assertEquals(List.of(c1, c2).stream().sorted().toList(), successorsOf(c));
        assertEquals(List.of(c), predecessorsOf(c2));
        assertEquals(List.of(office), predecessorsOf(hospital));
        assertEquals(LocalDate.of(2027, 4, 1), orgUnits.successions(c).successors().getFirst().effectiveDate());
    }

    @Test
    void aSuccessionNeedsUnitsThatExistOnTheDate() {
        String a = unit("A", null);
        String child = unit("Child", a);
        String b = unit("B", null);
        String later = orgUnits.create(new NewOrgUnit(null, "ja", ja("Later"), null, null, "2028-04-01",
                null, null, null, null)).unit().id();

        FuhitoException none = failure("2027-04-01", List.of(succeed(b, null, null)));
        assertEquals("invalid.required", none.code());
        assertEquals(Map.of("field", "successorIds", "step", "0"), none.params());
        assertEquals("invalid.required", failure("2027-04-01", List.of(succeed(b, List.of(), List.of()))).code());
        assertEquals("org_unit.successor_self", failure("2027-04-01", List.of(succeed(b, List.of(b), null))).code());
        assertEquals("org_unit.not_found", failure("2027-04-01", List.of(succeed(b, List.of("nope"), null))).code());
        assertEquals("org_unit.not_valid_on",
                failure("2027-04-01", List.of(succeed(b, List.of(later), null))).code());
        assertEquals(Map.of("field", "successorRef", "ref", "x", "step", "0"),
                failure("2027-04-01", List.of(succeed(b, null, List.of("x")))).params());
        // The unit ends as an end does, so the units below must move first.
        assertEquals("org_unit.has_children", failure("2027-04-01", List.of(succeed(a, List.of(b), null))).code());
        assertEquals(List.of(), store.successions);
        assertEquals(List.of(child), List.of(orgUnits.get(child, "2027-04-01").unit().id()));
    }

    @Test
    void aStepCanMoveAUnitThatAnEarlierStepCreated() {
        String a = unit("A", null);

        apply("2027-04-01", List.of(create("b", "B", null, null), move(null, "b", a, null),
                new ReorganizationStep("change", null, null, "b", null, null, null, null, "ja", ja("B2"), null, null, null, null, null, null, null)));

        assertEquals(List.of("A", "B2 < A"), tree("2027-04-01"));
    }

    @Test
    void aStepOfAStoredUnitNamesTheVersionThatTheCallerRead() {
        String a = unit("A", null);
        String b = unit("B", null);
        long current = orgUnits.version(a);

        // Every step of one unit names the version of before the request.
        apply("2027-04-01", List.of(change(a, "A2"), move(a, null, b, null)));

        // The units are locked at once before the first check, and the new parent does not change.
        assertEquals(List.of(List.of(a)), locked);
        assertEquals(List.of("B", "A2 < B"), tree("2027-04-01"));
        FuhitoException none = failure("2028-04-01", List.of(change(b, "B2"), new ReorganizationStep("end", null, a,
                null, null, null, null, null, "ja", ja(null), null, null, null, null, null, null, null)));
        assertEquals(FuhitoException.Reason.INVALID, none.reason());
        assertEquals("version.required", none.code());
        assertEquals(Map.of("step", "1"), none.params());
        FuhitoException old = failure("2028-04-01", List.of(new ReorganizationStep("end", null, a, null, current - 1,
                null, null, null, "ja", ja(null), null, null, null, null, null, null, null)));
        assertEquals(FuhitoException.Reason.CONFLICT, old.reason());
        assertEquals(Map.of("version", Long.toString(current), "step", "0"), old.params());
        // The check comes before every step, so the change of the first step was not written.
        assertEquals(List.of("B", "A2 < B"), tree("2028-04-01"));
    }

    @Test
    void aFailureNamesTheStep() {
        String a = unit("A", null);
        String child = unit("Child", a);

        FuhitoException e = failure("2027-04-01", List.of(change(child, "Child 2"), end(a)));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("org_unit.has_children", e.code());
        assertEquals("1", e.params().get("step"));
        assertEquals(a, e.params().get("id"));
    }

    @Test
    void rejectsBadRequests() {
        String a = unit("A", null);

        assertEquals("invalid.required", failure(null, List.of(end(a))).code());
        assertEquals("invalid.date", failure("soon", List.of(end(a))).code());
        assertEquals("invalid.required", failure("2027-04-01", null).code());
        FuhitoException empty = failure("2027-04-01", List.of());
        assertEquals("invalid.required", empty.code());
        assertEquals(Map.of("field", "steps"), empty.params());
        assertNull(empty.params().get("step"));

        FuhitoException noOp = failure("2027-04-01", List.of(new ReorganizationStep(null, null, a, null, null, null,
                null, null, "ja", ja(null), null, null, null, null, null, null, null)));
        assertEquals("invalid.required", noOp.code());
        assertEquals(Map.of("field", "op", "step", "0"), noOp.params());
        FuhitoException badOp = failure("2027-04-01", List.of(new ReorganizationStep("merge", null, a, null, null,
                null, null, null, "ja", ja(null), null, null, null, null, null, null, null)));
        assertEquals("invalid.choice", badOp.code());
        assertEquals(Map.of("field", "op", "value", "merge", "step", "0"), badOp.params());
        List<ReorganizationStep> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals("invalid.required", failure("2027-04-01", withNull).code());

        assertEquals(Map.of("field", "id", "step", "0"), failure("2027-04-01", List.of(end(null))).params());
        assertEquals(Map.of("field", "id", "step", "0"),
                failure("2027-04-01", List.of(change(null, "x"))).params());
        assertEquals(Map.of("field", "id", "step", "0"),
                failure("2027-04-01", List.of(move(null, null, a, null))).params());
    }

    @Test
    void rejectsAnUnknownOrRepeatedRef() {
        String a = unit("A", null);

        FuhitoException unknownParent = failure("2027-04-01", List.of(create(null, "B", null, "nope")));
        assertEquals("reorganization.ref_unknown", unknownParent.code());
        assertEquals(Map.of("field", "parentRef", "ref", "nope", "step", "0"), unknownParent.params());
        FuhitoException unknownUnit = failure("2027-04-01", List.of(move(null, "later", a, null),
                create("later", "L", null, null)));
        assertEquals(Map.of("field", "unitRef", "ref", "later", "step", "0"), unknownUnit.params());

        FuhitoException repeated = failure("2027-04-01", List.of(create("x", "X1", null, null),
                create("x", "X2", null, null)));
        assertEquals("reorganization.ref_taken", repeated.code());
        assertEquals(Map.of("ref", "x", "step", "1"), repeated.params());
    }
}

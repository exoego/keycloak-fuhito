package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReorganizationServiceTest {

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final OrgUnitService orgUnits = new OrgUnitService(store, new MemoryAssignmentStore(),
            new MemoryRefItemStore(), Clock.systemUTC());
    private final ReorganizationService service = new ReorganizationService(orgUnits);

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    private static ReorganizationStep create(String ref, String name, String parentId, String parentRef) {
        return new ReorganizationStep("create", ref, null, null, parentId, parentRef, null, name, null, null, null,
                null, null, null, null);
    }

    private static ReorganizationStep change(String id, String name) {
        return new ReorganizationStep("change", null, id, null, null, null, null, name, null, null, null, null, null,
                null, null);
    }

    private static ReorganizationStep move(String id, String unitRef, String parentId, String parentRef) {
        return new ReorganizationStep("move", null, id, unitRef, parentId, parentRef, null, null, null, null, null,
                null, null, null, null);
    }

    private static ReorganizationStep end(String id) {
        return new ReorganizationStep("end", null, id, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private List<String> tree(String asOf) {
        return orgUnits.list(asOf, false).stream()
                .map(s -> s.version().name() + (s.parentId() == null ? "" : " < "
                        + orgUnits.get(s.parentId(), asOf).version().name()))
                .toList();
    }

    private FuhitoException failure(String date, List<ReorganizationStep> steps) {
        return assertThrows(FuhitoException.class, () -> service.apply(date, steps));
    }

    @Test
    void appliesEveryKindOfStepOnOneDate() {
        String hospital = unit("Hospital", null);
        String medicine = unit("Medicine", hospital);
        String surgery = unit("Surgery", hospital);
        String oldOffice = unit("Old Office", hospital);

        List<ReorganizationService.Result> results = service.apply("2027-04-01", List.of(
                change(hospital, "General Hospital"),
                create("clinical", "Clinical Division", hospital, null),
                move(medicine, null, null, "clinical"),
                move(surgery, null, null, "clinical"),
                create(null, "Outpatient", null, "clinical"),
                end(oldOffice)));

        assertEquals(List.of("change", "create", "move", "move", "create", "end"),
                results.stream().map(ReorganizationService.Result::op).toList());
        assertEquals("General Hospital", results.get(0).unit().version().name());
        assertEquals(results.get(1).unit().unit().id(), results.get(2).unit().parentId());
        assertEquals("Old Office", results.get(5).unit().version().name());
        assertEquals(List.of("Hospital", "Medicine < Hospital", "Old Office < Hospital", "Surgery < Hospital"),
                tree("2027-03-31"));
        assertEquals(List.of("General Hospital", "Clinical Division < General Hospital",
                "Medicine < Clinical Division", "Outpatient < Clinical Division", "Surgery < Clinical Division"),
                tree("2027-04-01"));
    }

    @Test
    void aStepCanMoveAUnitThatAnEarlierStepCreated() {
        String a = unit("A", null);

        service.apply("2027-04-01", List.of(create("b", "B", null, null), move(null, "b", a, null),
                new ReorganizationStep("change", null, null, "b", null, null, null, "B2", null, null, null, null,
                        null, null, null)));

        assertEquals(List.of("A", "B2 < A"), tree("2027-04-01"));
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
                null, null, null, null, null, null, null, null, null)));
        assertEquals("invalid.required", noOp.code());
        assertEquals(Map.of("field", "op", "step", "0"), noOp.params());
        FuhitoException badOp = failure("2027-04-01", List.of(new ReorganizationStep("merge", null, a, null, null,
                null, null, null, null, null, null, null, null, null, null)));
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

package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Alice has the employments e1 and e2. Bob has e3.
class EndedOnStartChangesTest {

    private final MemoryAssignmentStore store = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employments = new MemoryEmploymentStore();

    @BeforeEach
    void employTwoPeople() {
        store.employments = employments;
        employ("e1", "alice");
        employ("e2", "alice");
        employ("e3", "bob");
    }

    private void employ(String id, String personId) {
        employments.employments.put(id, new Employment(id, personId, null, Employer.legalEntity("x"),
                ValidPeriod.startingAt(LocalDate.of(2020, 4, 1)), null, null, null, null, 1));
    }

    private static Assignment row(String rowId, String assignmentId, String employmentId, String from, String to,
            boolean primary) {
        return new Assignment(rowId, assignmentId, employmentId, "ward",
                new ValidPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), null, null, null,
                null, null, false, primary, null, 0, Map.of(), null, 1);
    }

    private Assignment stored(String assignmentId, String employmentId, String from, String to, boolean primary) {
        Assignment row = row(assignmentId + "-1", assignmentId, employmentId, from, to, primary);
        store.insert(row);
        return row;
    }

    private void replace(Assignment row) {
        store.deleteAll(List.of(row.rowId()));
        store.insert(row);
    }

    private static List<PropagatedChange> propagated(String... assignmentIds) {
        return List.of(assignmentIds).stream()
                .map(id -> new PropagatedChange(DerivedValue.ASSIGNMENT_ENDED_ON_START, id))
                .toList();
    }

    @Test
    void anEndThatMovesEarlierChangesTheAssignmentsThatStartOnTheOldAndOnTheNewEnd() {
        Assignment a = stored("a", "e1", "2024-04-01", "2026-10-01", true);
        stored("onOld", "e1", "2026-10-01", null, true);
        stored("onNew", "e2", "2026-07-01", null, true);
        stored("otherFlag", "e2", "2026-07-01", null, false);
        stored("ofBob", "e3", "2026-07-01", null, true);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("a"));

        replace(row(a.rowId(), "a", "e1", "2024-04-01", "2026-07-01", true));

        assertEquals(Map.of("a", propagated("onNew", "onOld")), changes.changedBy());
        assertEquals(List.of(List.of("onNew", "onOld")), store.countedUp);
        assertEquals(List.of(2L, 2L, 1L, 1L), List.of(store.rows("onOld").getFirst().version(),
                store.rows("onNew").getFirst().version(), store.rows("otherFlag").getFirst().version(),
                store.rows("ofBob").getFirst().version()));
    }

    @Test
    void aChangeOfTheFlagOfTheLastRowChangesTheAssignmentsThatStartOnTheEnd() {
        stored("a", "e1", "2024-04-01", "2026-04-01", true);
        stored("primary", "e2", "2026-04-01", null, true);
        stored("post", "e2", "2026-04-01", null, false);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("a"));

        replace(row("a-1", "a", "e1", "2024-04-01", "2025-04-01", true));
        store.insert(row("a-2", "a", "e1", "2025-04-01", "2026-04-01", false));

        assertEquals(propagated("post", "primary"), changes.changed());
    }

    @Test
    void aChangeOfAnEarlierRowOrOfTheFirstRowChangesNoOtherAssignment() {
        stored("a", "e1", "2024-04-01", "2026-04-01", true);
        stored("next", "e1", "2026-04-01", null, true);
        stored("before", "e1", "2023-04-01", "2024-04-01", true);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("a"));

        // The start and the flag of the first row count only for the list of the assignment itself.
        replace(row("a-1", "a", "e1", "2024-05-01", "2025-04-01", false));
        store.insert(row("a-2", "a", "e1", "2025-04-01", "2026-04-01", true));

        assertEquals(List.of(), changes.changed());
        assertEquals(List.of(), store.countedUp);
    }

    @Test
    void aNewAndARemovedAssignmentChangeTheAssignmentsThatStartOnTheirEnd() {
        Assignment removed = stored("removed", "e1", "2024-04-01", "2026-04-01", false);
        stored("next", "e2", "2026-04-01", null, false);
        stored("later", "e2", "2027-04-01", null, true);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("removed"));
        Assignment added = row("added-1", "added", "e1", "2026-04-01", "2027-04-01", true);
        Assignment open = row("open-1", "open", "e1", "2027-04-01", null, true);

        store.deleteAll(List.of(removed.rowId()));
        store.insertAll(List.of(added, open));
        changes.noteNew(List.of(added, open));

        assertEquals(Map.of("removed", propagated("next"), "added", propagated("later")), changes.changedBy());
    }

    @Test
    void aNewAssignmentGetsNoPropagatedChangeAndAnAssignmentIsNamedOnceUnderItsFirstCause() {
        Assignment a = stored("a", "e1", "2024-04-01", "2026-10-01", true);
        stored("c", "e2", "2026-10-01", null, true);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("a"));

        // A move of A in July. The new assignment B starts on the new end of A and ends on the start of C.
        replace(row(a.rowId(), "a", "e1", "2024-04-01", "2026-07-01", true));
        Assignment b = row("b-1", "b", "e1", "2026-07-01", "2026-10-01", true);
        store.insert(b);
        changes.noteNew(List.of(b));

        assertEquals(Map.of("a", propagated("c")), changes.changedBy());
        assertEquals(List.of(List.of("c")), store.countedUp);
    }

    @Test
    void aWriteThatKeepsTheEndReadsNothingMore() {
        stored("a", "e1", "2024-04-01", "2026-04-01", true);
        EndedOnStartChanges changes = new EndedOnStartChanges(store);
        changes.note(store.rows("a"));
        changes.noteNew(List.of(row("open-1", "open", "e1", "2026-04-01", null, true)));

        assertEquals(List.of(), changes.changed());
        assertEquals(List.of(), new EndedOnStartChanges(store).changed());
        assertEquals(List.of(), store.countedUp);
    }
}

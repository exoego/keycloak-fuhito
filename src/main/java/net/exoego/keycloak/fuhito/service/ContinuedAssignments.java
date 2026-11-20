package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;

/**
 * Keeps a write from removing an assignment that another assignment continues, as the delete of an assignment
 * does. An earlier end of an employment removes each assignment of it that would start on the end or later. The sync
 * from user attributes removes an assignment that never held.
 */
final class ContinuedAssignments {

    private ContinuedAssignments() {
    }

    /**
     * Requires that no assignment that a write keeps continues one that the write removes with all its rows.
     *
     * @param employments the storage of employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param personIds the people of the removed assignments. An assignment continues only one of the same person.
     * @param removed the IDs of the assignments that the write removes
     * @throws FuhitoException as the delete of the first such assignment by ID does
     */
    static void requireNoneRemoved(EmploymentStore employments, AssignmentStore assignments,
            Collection<String> personIds, Set<String> removed) {
        if (removed.isEmpty()) {
            return;
        }
        List<String> ofPeople = employments.findByPeople(personIds).stream().map(Employment::id).toList();
        assignments.rowsOfEmployments(ofPeople).stream()
                .filter(row -> row.continuesAssignmentId() != null && removed.contains(row.continuesAssignmentId())
                        && !removed.contains(row.assignmentId()))
                .map(Assignment::continuesAssignmentId)
                .min(Comparator.naturalOrder())
                .ifPresent(id -> {
                    throw RemovalService.inUse(ResourceRemoval.Kind.ASSIGNMENT, id,
                            List.of(ResourceRemoval.CONTINUATION));
                });
    }
}

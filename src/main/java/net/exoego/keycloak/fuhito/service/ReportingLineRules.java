package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * The rules of a new reporting line against its assignment, which a write and a restore both check.
 */
final class ReportingLineRules {

    private ReportingLineRules() {
    }

    /**
     * Requires a manager who is not the person of the assignment.
     *
     * @param managerPersonId the ID of the manager
     * @param personId the ID of the person of the assignment, or null when its employment is not found
     * @throws FuhitoException when the manager is the person
     */
    static void requireNotSelf(String managerPersonId, String personId) {
        if (managerPersonId.equals(personId)) {
            throw FuhitoException.invalid("reporting_line.self", Map.of("managerPersonId", managerPersonId));
        }
    }

    /**
     * Requires that a new solid line shares no date with another solid line of its assignment.
     *
     * @param assignmentId the ID of the assignment
     * @param period the period of the new line
     * @param others the other lines of the assignment
     * @throws FuhitoException when a solid line of them holds on a date of the period
     */
    static void requireNoOtherSolid(String assignmentId, ValidPeriod period, List<ReportingLine> others) {
        others.stream()
                .filter(other -> other.kind() == ReportingLine.Kind.SOLID && other.period().overlaps(period))
                .findFirst()
                .ifPresent(other -> {
                    throw FuhitoException.conflict("reporting_line.solid_exists",
                            Map.of("assignmentId", assignmentId, "lineId", other.id()));
                });
    }
}

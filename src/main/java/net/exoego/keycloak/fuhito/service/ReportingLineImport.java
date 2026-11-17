package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ReportingLine.Kind;
import net.exoego.keycloak.fuhito.service.ImportDocument.ReportingLineRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The reporting lines of one document. A row is matched by assignment, manager, and kind, or by ID.
 * A new solid manager ends the solid line that the assignment has on the date.
 */
final class ReportingLineImport {

    static final String REPORTING_LINES = "reportingLines";

    private final ReportingLineService lines;
    private final ImportService.People people;
    private final ExternalIdService externalIds;
    private final LocalDate date;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();

    ReportingLineImport(ReportingLineService lines, ImportService.People people, ExternalIdService externalIds,
            String effectiveDate, String defaultSource, ImportReport report) {
        this.lines = lines;
        this.people = people;
        this.externalIds = externalIds;
        this.date = Checks.date("effectiveDate", effectiveDate);
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<ReportingLineRow> rows) {
        report.startList(REPORTING_LINES);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(REPORTING_LINES, apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(REPORTING_LINES, i, e);
            }
        }
    }

    private Outcome apply(ReportingLineRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        Optional<ReportingLine> byId = row.id() == null ? Optional.empty() : Optional.of(lines.find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id()))));
        String assignmentId = assignmentId(row, source, byId);
        String managerId = managerId(row, source, byId);
        Kind kind = Checks.optionalEnum("kind", Kind.class, row.kind(), byId.map(ReportingLine::kind)
                .orElse(Kind.SOLID));
        if (byId.isPresent() && !(byId.get().assignmentId().equals(assignmentId)
                && byId.get().managerPersonId().equals(managerId) && byId.get().kind() == kind)) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", managerId));
        }
        if (!seen.add(assignmentId + "\u0000" + managerId + "\u0000" + kind)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", managerId));
        }
        // The line of the date, or the first one after it for a document that comes again.
        Optional<ReportingLine> stored = byId.isPresent() ? byId : lines.ofAssignment(assignmentId).stream()
                .filter(line -> line.managerPersonId().equals(managerId) && line.kind() == kind)
                .filter(line -> line.period().contains(date) || line.period().from().isAfter(date))
                .min(Comparator.comparing(line -> line.period().from()));
        if (stored.isPresent()) {
            ReportingLine line = stored.get();
            if (row.validTo() == null || Objects.equals(row.validTo(), Objects.toString(line.period().to(), null))) {
                return Outcome.UNCHANGED;
            }
            report.write(new Write.OfReportingLine(false, "/end", lines.end(line.id(), row.validTo())));
            return Outcome.CHANGED;
        }
        LocalDate from = row.validFrom() == null ? date : Checks.date("validFrom", row.validFrom());
        if (kind == Kind.SOLID) {
            for (ReportingLine other : lines.ofAssignment(assignmentId)) {
                if (other.kind() == Kind.SOLID && other.period().contains(from)
                        && other.period().from().isBefore(from)) {
                    report.write(new Write.OfReportingLine(false, "/end", lines.end(other.id(), from.toString())));
                }
            }
        }
        report.write(new Write.OfReportingLine(true, "", lines.add(assignmentId, managerId, kind.name(),
                from.toString(), row.validTo())));
        return Outcome.CREATED;
    }

    private String assignmentId(ReportingLineRow row, String source, Optional<ReportingLine> byId) {
        if (row.assignmentExternalId() != null && row.assignmentId() != null) {
            throw FuhitoException.invalid("import.assignment_twice", Map.of(
                    "assignmentExternalId", row.assignmentExternalId(), "assignmentId", row.assignmentId()));
        }
        if (row.assignmentId() != null) {
            return row.assignmentId();
        }
        if (row.assignmentExternalId() == null) {
            return byId.map(ReportingLine::assignmentId).orElseThrow(() -> FuhitoException.invalid(
                    "invalid.required", Map.of("field", "assignmentExternalId")));
        }
        return externalIds.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.assignmentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.assignment_unknown",
                        Map.of("assignmentExternalId", row.assignmentExternalId())));
    }

    // A user who has no person yet gets one, as for an employment.
    private String managerId(ReportingLineRow row, String source, Optional<ReportingLine> byId) {
        long given = Stream.of(row.managerPersonExternalId(), row.managerPersonId(),
                row.managerUsername()).filter(Objects::nonNull).count();
        if (given > 1) {
            throw FuhitoException.invalid("import.manager_twice", Map.of());
        }
        if (row.managerPersonId() != null) {
            return row.managerPersonId();
        }
        if (row.managerPersonExternalId() != null) {
            return externalIds.resourceOf(ExternalId.Kind.PERSON, source, row.managerPersonExternalId())
                    .orElseThrow(() -> FuhitoException.invalid("import.person_unknown",
                            Map.of("personExternalId", row.managerPersonExternalId())));
        }
        if (row.managerUsername() != null) {
            String userId = people.users().findIdByUsername(row.managerUsername()).orElseThrow(() ->
                    FuhitoException.invalid("import.username_unknown", Map.of("username", row.managerUsername())));
            PersonService.Resolved resolved = people.personService().forUser(userId);
            if (resolved.created() != null) {
                report.write(new Write.OfPerson(resolved.created()));
            }
            return resolved.person().id();
        }
        return byId.map(ReportingLine::managerPersonId).orElseThrow(() -> FuhitoException.invalid(
                "invalid.required", Map.of("field", "managerPersonExternalId")));
    }
}

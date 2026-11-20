package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * The reporting lines of one document. A row is matched by source and external ID, or by ID. The assignment, the
 * manager, the kind, and the start of a stored line do not change. A new solid line fails while the assignment has a
 * solid line on the same dates, as in {@code POST /reporting-lines}.
 */
final class ReportingLineImport {

    static final String REPORTING_LINES = "reportingLines";

    private record Line(ReportingLineRow row, String source, String assignmentId, String managerId, Kind kind,
            Optional<ReportingLine> stored) {
    }

    private final ReportingLineService lines;
    private final ImportService.People people;
    private final ExternalIdService externalIds;
    private final LocalDate date;
    private final String defaultSource;
    private final ImportReport report;
    private final ImportMatch match;
    private final Runnable rowDone;

    ReportingLineImport(ReportingLineService lines, ImportService.People people, ExternalIdService externalIds,
            String effectiveDate, String defaultSource, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.lines = lines;
        this.people = people;
        this.externalIds = externalIds;
        this.date = Checks.date("effectiveDate", effectiveDate);
        this.defaultSource = defaultSource;
        this.report = report;
        this.match = new ImportMatch(externalIds, ExternalId.Kind.REPORTING_LINE);
    }

    void run(List<ReportingLineRow> rows) {
        report.startList(REPORTING_LINES);
        // New lines go last, so that the row that ends a solid line can come before or after the row of the next one.
        Map<Integer, Line> added = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                Line line = resolve(EmptyValues.checked(rows.get(i)));
                if (line.stored().isEmpty()) {
                    added.put(i, line);
                } else {
                    report.count(REPORTING_LINES, change(line.stored().get(), line.row()));
                }
            } catch (FuhitoException e) {
                report.error(REPORTING_LINES, i, e);
            }
            rowDone.run();
        }
        for (Map.Entry<Integer, Line> entry : added.entrySet()) {
            try {
                report.count(REPORTING_LINES, add(entry.getValue()));
            } catch (FuhitoException e) {
                report.error(REPORTING_LINES, entry.getKey(), e);
            }
            rowDone.run();
        }
    }

    private Line resolve(ReportingLineRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        Optional<ReportingLine> stored = match.stored(source, row.id(), row.externalId(),
                id -> lines.find(id).isPresent()).flatMap(lines::find);
        String assignmentId = assignmentId(row, source, stored);
        String managerId = managerId(row, source, stored);
        Kind kind = Checks.optionalEnum("kind", Kind.class, row.kind(),
                stored.filter(line -> !row.isNull("kind")).map(ReportingLine::kind).orElse(Kind.SOLID));
        stored.ifPresent(line -> {
            ImportMatch.fixed(line.id(), "assignmentId", line.assignmentId(), assignmentId);
            ImportMatch.fixed(line.id(), "managerPersonId", line.managerPersonId(), managerId);
            ImportMatch.fixed(line.id(), "kind", line.kind(), kind);
            ImportMatch.start(row, line.id(), line.period().from(), row.validFrom());
        });
        return new Line(row, source, assignmentId, managerId, kind, stored);
    }

    // With no end of its own, a line ends with its assignment. A line can only end earlier.
    private Outcome change(ReportingLine line, ReportingLineRow row) {
        LocalDate end = line.period().to();
        String validTo = row.or("validTo", row.validTo(), Objects.toString(end, null));
        if (validTo == null) {
            ImportMatch.clearedEnd(line.id(), end,
                    people.assignments().rows(line.assignmentId()).getLast().period().to());
            return Outcome.UNCHANGED;
        }
        if (validTo.equals(Objects.toString(end, null))) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfReportingLine(false, "/end", lines.end(line.id(), validTo)));
        return Outcome.CHANGED;
    }

    private Outcome add(Line line) {
        ReportingLineRow row = line.row();
        report.write(new Write.OfReportingLine(true, "", match.create(line.source(), row.externalId(),
                () -> lines.add(line.assignmentId(), line.managerId(), line.kind().name(),
                        row.validFrom() == null ? date.toString() : row.validFrom(), row.validTo()),
                ReportingLine::id)));
        return Outcome.CREATED;
    }

    private String assignmentId(ReportingLineRow row, String source, Optional<ReportingLine> stored) {
        if (row.assignmentExternalId() != null && row.assignmentId() != null) {
            throw FuhitoException.invalid("import.assignment_twice", Map.of(
                    "assignmentExternalId", row.assignmentExternalId(), "assignmentId", row.assignmentId()));
        }
        if (row.assignmentId() != null) {
            return row.assignmentId();
        }
        if (row.assignmentExternalId() == null) {
            return stored.filter(line -> !row.isNull("assignmentExternalId") && !row.isNull("assignmentId"))
                    .map(ReportingLine::assignmentId).orElseThrow(() -> FuhitoException.invalid(
                            "invalid.required", Map.of("field", "assignmentExternalId")));
        }
        return externalIds.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.assignmentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.assignment_unknown",
                        Map.of("assignmentExternalId", row.assignmentExternalId())));
    }

    // A user who has no person yet gets one, as for an employment.
    private String managerId(ReportingLineRow row, String source, Optional<ReportingLine> stored) {
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
        boolean cleared = row.isNull("managerPersonExternalId") || row.isNull("managerPersonId")
                || row.isNull("managerUsername");
        return stored.filter(line -> !cleared).map(ReportingLine::managerPersonId).orElseThrow(
                () -> FuhitoException.invalid("invalid.required", Map.of("field", "managerPersonExternalId")));
    }
}

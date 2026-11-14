package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The employments and the assignments of one document.
 */
final class PeopleImport {

    private static final String EMPLOYMENTS = "employments";
    private static final String ASSIGNMENTS = "assignments";

    private final ImportService.People people;
    private final OrgUnitStore orgUnits;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;

    PeopleImport(ImportService.People people, OrgUnitStore orgUnits, String effectiveDate, String defaultSource,
            ImportReport report) {
        this.people = people;
        this.orgUnits = orgUnits;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void employments(List<EmploymentRow> rows) {
        report.startList(EMPLOYMENTS);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(EMPLOYMENTS, importEmployment(Checks.required("row", rows.get(i)), seen));
            } catch (FuhitoException e) {
                report.error(EMPLOYMENTS, i, e);
            }
        }
    }

    void assignments(List<AssignmentRow> rows) {
        // A row that ends or gives up the primary flag is applied before a row that takes the flag.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> rows.get(i) != null && rows.get(i).validTo() == null
                && Boolean.TRUE.equals(rows.get(i).primary())));
        report.startList(ASSIGNMENTS);
        Set<String> seen = new HashSet<>();
        for (int i : order) {
            try {
                AssignmentRow row = Checks.required("row", rows.get(i));
                requireFirst(seen, row.source(), row.externalId());
                report.count(ASSIGNMENTS, importAssignment(row));
            } catch (FuhitoException e) {
                report.error(ASSIGNMENTS, i, e);
            }
        }
    }

    private void requireFirst(Set<String> seen, String source, String externalId) {
        Checks.required("externalId", externalId);
        if (!seen.add((source == null ? defaultSource : source) + "\\u0000" + externalId)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", externalId));
        }
    }

    private Outcome importEmployment(EmploymentRow row, Set<String> seen) {
        requireFirst(seen, row.source(), row.externalId());
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String userId = userId(row);
        Optional<Employment> stored = people.employments().findByExternalId(source, row.externalId());
        if (stored.isEmpty()) {
            EmploymentView created = people.employmentService().start(new NewEmployment(userId,
                    row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.staffNumber(),
                    row.employmentType(), row.grade(), row.status(), row.workPercent(), row.attributes(),
                    row.externalId(), source));
            String id = created.employment().id();
            written(true, "", created);
            if (row.validTo() != null && row.endReason() != null) {
                written(false, "/end",
                        people.employmentService().end(id, row.validTo(), row.endReason()));
            }
            return Outcome.CREATED;
        }
        Employment employment = stored.get();
        String id = employment.id();
        if (!employment.userId().equals(userId)) {
            throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", row.externalId()));
        }
        EmploymentView before = people.employmentService().get(id);
        boolean changed = false;
        // An employment that ended before the effective date has no terms to compare on that date.
        boolean endsBefore = employment.period().to() != null
                && !employment.period().to().isAfter(Checks.date("effectiveDate", effectiveDate));
        if (!endsBefore) {
            EmploymentView.TermsView terms = before.terms().stream()
                    .filter(t -> t.terms().period().contains(Checks.date("effectiveDate", effectiveDate)))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameTerms(row, terms)) {
                written(false, "/terms", people.employmentService().changeTerms(id, new TermsChange(
                        effectiveDate, row.employmentType(), row.grade(), row.status(), row.workPercent(),
                        row.attributes())));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(employment.period().to(), null);
        boolean sameEnd = Objects.equals(row.validTo(), storedEnd)
                && (row.endReason() == null || row.endReason().equals(before.endReason()));
        if (row.validTo() != null && !sameEnd) {
            written(false, "/end", people.employmentService().end(id, row.validTo(), row.endReason()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private String userId(EmploymentRow row) {
        if (row.userId() != null) {
            return row.userId();
        }
        String username = Checks.required("username", row.username());
        return people.users().findIdByUsername(username)
                .orElseThrow(() -> FuhitoException.invalid("import.username_unknown", Map.of("username", username)));
    }

    private static boolean sameTerms(EmploymentRow row, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = row.status() == null ? "ACTIVE" : row.status();
        return Objects.equals(row.employmentType(), terms.employmentType())
                && Objects.equals(row.grade(), terms.grade())
                && status.equals(stored.status().name())
                && sameNumber(row.workPercent(), stored.workPercent())
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(stored.attributes());
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void written(boolean created, String suffix, EmploymentView state) {
        report.write(new Write.OfEmployment(created, suffix, state));
    }

    private Outcome importAssignment(AssignmentRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String employmentExternalId = Checks.required("employmentExternalId", row.employmentExternalId());
        String orgUnitExternalId = Checks.required("orgUnitExternalId", row.orgUnitExternalId());
        String employmentId = people.employments().findByExternalId(source, employmentExternalId)
                .map(Employment::id)
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", employmentExternalId)));
        String orgUnitId = orgUnits.findUnitByExternalId(source, orgUnitExternalId)
                .map(OrgUnit::id)
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", orgUnitExternalId)));
        AssignmentValues values = new AssignmentValues(employmentId, orgUnitId,
                row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.jobTitle(),
                row.occupation(), row.kind(), row.head(), row.primary(), false, row.allocationPercent(),
                row.displayOrder(), row.attributes(), row.externalId(), source);

        Optional<String> stored = people.assignments().findAssignmentIdByExternalId(source, row.externalId());
        if (stored.isEmpty()) {
            AssignmentView created = people.assignmentService().add(values);
            written(true, "", created);
            return Outcome.CREATED;
        }
        String id = stored.get();
        AssignmentView before = people.assignmentService().get(id);
        Assignment first = before.rows().getFirst().row();
        if (!first.employmentId().equals(employmentId) || !first.orgUnitId().equals(orgUnitId)) {
            throw FuhitoException.conflict("import.assignment_mismatch", Map.of("externalId", row.externalId()));
        }
        boolean changed = false;
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        boolean endsBefore = !before.period().endsAfter(date);
        if (!endsBefore) {
            AssignmentView.Row current = before.rows().stream()
                    .filter(r -> r.row().period().contains(date))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("assignment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameDetails(row, current)) {
                written(false, "/details", people.assignmentService().changeDetails(id,
                        new AssignmentValues(null, null, effectiveDate, null, row.jobTitle(), row.occupation(),
                                row.kind(), row.head(), row.primary(), false, row.allocationPercent(),
                                row.displayOrder(), row.attributes(), null, null)));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(before.period().to(), null);
        if (row.validTo() != null && !row.validTo().equals(storedEnd)) {
            written(false, "/end", people.assignmentService().end(id, row.validTo()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private static boolean sameDetails(AssignmentRow row, AssignmentView.Row current) {
        Assignment stored = current.row();
        return Objects.equals(row.jobTitle(), current.jobTitle())
                && Objects.equals(row.occupation(), current.occupation())
                && Objects.equals(row.kind(), current.kind())
                && Boolean.TRUE.equals(row.head()) == stored.head()
                && Boolean.TRUE.equals(row.primary()) == stored.primary()
                && sameNumber(row.allocationPercent(), stored.allocationPercent())
                && (row.displayOrder() == null ? 0 : row.displayOrder()) == stored.displayOrder()
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(stored.attributes());
    }

    private void written(boolean created, String suffix, AssignmentView state) {
        report.write(new Write.OfAssignment(created, suffix, state));
    }
}

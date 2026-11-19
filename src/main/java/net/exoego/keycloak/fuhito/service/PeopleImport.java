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
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.service.ImportDocument.PersonRow;

/**
 * The people, the employments, and the assignments of one document.
 */
final class PeopleImport {

    private static final String PEOPLE = "people";
    private static final String EMPLOYMENTS = "employments";
    private static final String ASSIGNMENTS = "assignments";

    private final ImportService.People people;
    private final ExternalIdService externalIds;
    private final OrgUnitStore orgUnits;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;

    PeopleImport(ImportService.People people, ExternalIdService externalIds, OrgUnitStore orgUnits,
            String effectiveDate, String defaultSource, ImportReport report) {
        this.people = people;
        this.externalIds = externalIds;
        this.orgUnits = orgUnits;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void people(List<PersonRow> rows) {
        report.startList(PEOPLE);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                PersonRow row = Checks.required("row", rows.get(i));
                requireFirst(seen, row.source(), row.externalId(), row.id());
                report.count(PEOPLE, importPerson(row));
            } catch (FuhitoException e) {
                report.error(PEOPLE, i, e);
            }
        }
    }

    private Outcome importPerson(PersonRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        PersonService service = people.personService();
        String userId = row.userId() != null || row.username() != null
                ? userId(row.userId(), row.username()) : null;
        Optional<Person> stored = row.id() == null
                ? externalIds.resourceOf(ExternalId.Kind.PERSON, source, row.externalId()).flatMap(service::find)
                : Optional.of(byId(row, source));
        if (stored.isEmpty()) {
            // A row never finds a person by its user. The file must name the person of the user.
            Optional<Person> ofUser = userId == null ? Optional.empty() : service.ofUser(userId);
            if (ofUser.isPresent()) {
                throw FuhitoException.conflict("import.user_taken",
                        Map.of("userId", userId, "personId", ofUser.get().id()));
            }
            report.write(new Write.OfPerson(true, service.create(values(row, userId, source))));
            return Outcome.CREATED;
        }
        Person person = stored.get();
        String linked = userId != null ? userId : person.userId();
        PersonValues wanted = values(row, linked, source);
        boolean same = Objects.equals(linked, person.userId())
                && Objects.equals(PersonName.check(wanted.primaryLanguage(), wanted.names()), person.names())
                && (wanted.attributes() == null ? Map.of() : wanted.attributes()).equals(person.attributes());
        if (same) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfPerson(false, service.replace(person.id(), wanted)));
        return Outcome.CHANGED;
    }

    // A row with an ID changes that person. An external ID beside it must be one of that person.
    private Person byId(PersonRow row, String source) {
        Person person = people.personService().find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        requireNames(ExternalId.Kind.PERSON, row.id(), source, row.externalId());
        return person;
    }

    private void requireNames(ExternalId.Kind kind, String id, String source, String externalId) {
        if (externalId != null && !externalIds.resourceOf(kind, source, externalId).equals(Optional.of(id))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", id, "externalId", externalId));
        }
    }

    private static PersonValues values(PersonRow row, String userId, String source) {
        return new PersonValues(userId, row.primaryLanguage(), row.names(), row.attributes(), row.externalId(),
                source);
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
                requireFirst(seen, row.source(), row.externalId(), row.id());
                report.count(ASSIGNMENTS, importAssignment(row));
            } catch (FuhitoException e) {
                report.error(ASSIGNMENTS, i, e);
            }
        }
    }

    private String continuedEmployment(EmploymentRow row, String source) {
        if (row.continuesEmploymentExternalId() != null && row.continuesEmploymentId() != null) {
            throw FuhitoException.invalid("import.continues_twice", Map.of());
        }
        if (row.continuesEmploymentExternalId() == null) {
            return row.continuesEmploymentId();
        }
        return externalIds.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.continuesEmploymentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", row.continuesEmploymentExternalId())));
    }

    private String continuedAssignment(AssignmentRow row, String source) {
        if (row.continuesAssignmentExternalId() != null && row.continuesAssignmentId() != null) {
            throw FuhitoException.invalid("import.continues_twice", Map.of());
        }
        if (row.continuesAssignmentExternalId() == null) {
            return row.continuesAssignmentId();
        }
        return externalIds.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.continuesAssignmentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.assignment_unknown",
                        Map.of("assignmentExternalId", row.continuesAssignmentExternalId())));
    }

    // A row with an ID is known by it. Any other row needs an external ID.
    private void requireFirst(Set<String> seen, String source, String externalId, String id) {
        if (id != null) {
            if (!seen.add("\\u0000id\\u0000" + id)) {
                throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", id));
            }
            return;
        }
        Checks.required("externalId", externalId);
        if (!seen.add((source == null ? defaultSource : source) + "\\u0000" + externalId)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", externalId));
        }
    }

    private Outcome importEmployment(EmploymentRow row, Set<String> seen) {
        requireFirst(seen, row.source(), row.externalId(), row.id());
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String name = row.externalId() == null ? row.id() : row.externalId();
        Optional<Employment> stored = row.id() == null
                ? externalIds.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.externalId())
                        .flatMap(people.employments()::find)
                : Optional.of(employmentById(row, source));
        String personId = personId(row, name, source, stored);
        String employerId = employerId(row, source);
        String continues = continuedEmployment(row, source);
        if (stored.isEmpty()) {
            EmploymentView created = people.employmentService().start(new NewEmployment(personId,
                    row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.staffNumber(),
                    row.employmentType(), row.grade(), row.status(), row.leaveReason(), row.workPercent(),
                    row.contractEndDate(), row.attributes(), row.externalId(), source, employerId), continues);
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
        if (!employment.personId().equals(personId)) {
            throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", name));
        }
        // The employer never changes. A transfer to another employer is the way to move a person.
        if (employerId != null && !employerId.equals(employment.employerId())) {
            throw FuhitoException.conflict("import.employer_mismatch", Map.of("externalId", name));
        }
        if (continues != null && !continues.equals(employment.continuesEmploymentId())) {
            throw FuhitoException.conflict("import.continues_mismatch", Map.of("externalId", name));
        }
        EmploymentView before = people.employmentService().get(id);
        boolean changed = false;
        // An employment that ended by the effective date has no terms to compare. One that starts later can only
        // come again as it is, for example in the document that made it.
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        boolean endsBefore = employment.period().to() != null && !employment.period().to().isAfter(date);
        if (!endsBefore) {
            boolean later = employment.period().from().isAfter(date);
            LocalDate on = later ? employment.period().from() : date;
            EmploymentView.TermsView terms = before.terms().stream()
                    .filter(t -> t.terms().period().contains(on))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameTerms(row, terms)) {
                if (later) {
                    throw FuhitoException.notFound("employment.not_valid_on", Map.of("id", id, "date", effectiveDate));
                }
                written(false, "/terms", people.employmentService().changeTerms(id, new TermsChange(
                        effectiveDate, row.employmentType(), row.grade(), row.status(), row.leaveReason(),
                        row.workPercent(), row.contractEndDate(), row.attributes())));
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

    // A row with an ID changes that employment. An external ID beside it must be one of that employment.
    private Employment employmentById(EmploymentRow row, String source) {
        Employment employment = people.employments().find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        requireNames(ExternalId.Kind.EMPLOYMENT, row.id(), source, row.externalId());
        return employment;
    }

    private String employerId(EmploymentRow row, String source) {
        if (row.employerExternalId() != null && row.employerId() != null) {
            throw FuhitoException.invalid("import.employer_twice",
                    Map.of("employerExternalId", row.employerExternalId(), "employerId", row.employerId()));
        }
        if (row.employerId() != null) {
            if (orgUnits.findUnit(row.employerId()).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.employerId()));
            }
            return row.employerId();
        }
        return row.employerExternalId() == null ? null
                : externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.employerExternalId())
                        .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                                Map.of("orgUnitExternalId", row.employerExternalId())));
    }

    // A row names a person of the list of people, or a Keycloak user. The person of a user is used, and created
    // when the user has none. A row for a stored employment may name nobody.
    private String personId(EmploymentRow row, String name, String source, Optional<Employment> stored) {
        boolean namesUser = row.userId() != null || row.username() != null;
        if (row.personExternalId() != null && row.personId() != null) {
            throw FuhitoException.invalid("import.person_twice",
                    Map.of("personExternalId", row.personExternalId(), "personId", row.personId()));
        }
        if (row.personExternalId() != null || row.personId() != null) {
            String id = row.personId() != null
                    ? people.personService().find(row.personId()).map(Person::id)
                            .orElseThrow(() -> FuhitoException.invalid("import.id_unknown",
                                    Map.of("id", row.personId())))
                    : externalIds.resourceOf(ExternalId.Kind.PERSON, source, row.personExternalId())
                            .orElseThrow(() -> FuhitoException.invalid("import.person_unknown",
                                    Map.of("personExternalId", row.personExternalId())));
            if (namesUser && !userId(row.userId(), row.username())
                    .equals(people.personService().find(id).map(Person::userId).orElse(null))) {
                throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", name));
            }
            return id;
        }
        if (!namesUser && stored.isPresent()) {
            return stored.get().personId();
        }
        PersonService.Resolved resolved = people.personService().forUser(userId(row.userId(), row.username()));
        if (resolved.created() != null) {
            report.write(new Write.OfPerson(resolved.created()));
        }
        return resolved.person().id();
    }

    private String userId(String userId, String username) {
        if (userId != null) {
            return userId;
        }
        String name = Checks.required("username", username);
        return people.users().findIdByUsername(name)
                .orElseThrow(() -> FuhitoException.invalid("import.username_unknown", Map.of("username", name)));
    }

    private static boolean sameTerms(EmploymentRow row, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = row.status() == null ? "ACTIVE" : row.status();
        return Objects.equals(row.employmentType(), terms.employmentType())
                && Objects.equals(row.grade(), terms.grade())
                && status.equals(stored.status().name())
                && Objects.equals(row.leaveReason(), terms.leaveReason())
                && sameNumber(row.workPercent(), stored.workPercent())
                && Objects.equals(row.contractEndDate(), Objects.toString(stored.contractEndDate(), null))
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
        String name = row.externalId() == null ? row.id() : row.externalId();
        Optional<String> stored = row.id() == null
                ? externalIds.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.externalId())
                : Optional.of(assignmentById(row, source));
        Optional<Assignment> firstRow = stored.map(id -> people.assignments().rows(id).getFirst());
        String employmentId = employmentId(row, source, firstRow);
        String orgUnitId = orgUnitId(row, source, firstRow);
        AssignmentValues values = new AssignmentValues(employmentId, orgUnitId,
                row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.jobTitle(),
                row.occupation(), row.kind(), row.workLocation(), row.costCenter(), row.head(), row.primary(), false,
                row.allocationPercent(), row.displayOrder(), row.attributes(), row.externalId(), source);

        String continues = continuedAssignment(row, source);
        if (stored.isEmpty()) {
            written(true, "", people.assignmentService().add(values, continues));
            return Outcome.CREATED;
        }
        String id = stored.get();
        AssignmentView before = people.assignmentService().get(id);
        Assignment first = before.rows().getFirst().row();
        if (!first.employmentId().equals(employmentId) || !first.orgUnitId().equals(orgUnitId)) {
            throw FuhitoException.conflict("import.assignment_mismatch", Map.of("externalId", name));
        }
        if (continues != null && !continues.equals(first.continuesAssignmentId())) {
            throw FuhitoException.conflict("import.continues_mismatch", Map.of("externalId", name));
        }
        boolean changed = false;
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        boolean endsBefore = !before.period().endsAfter(date);
        if (!endsBefore) {
            boolean later = before.period().from().isAfter(date);
            LocalDate on = later ? before.period().from() : date;
            AssignmentView.Row current = before.rows().stream()
                    .filter(r -> r.row().period().contains(on))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("assignment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameDetails(row, current)) {
                if (later) {
                    throw FuhitoException.notFound("assignment.not_valid_on", Map.of("id", id, "date", effectiveDate));
                }
                written(false, "/details", people.assignmentService().changeDetails(id,
                        new AssignmentValues(null, null, effectiveDate, null, row.jobTitle(), row.occupation(),
                                row.kind(), row.workLocation(), row.costCenter(), row.head(), row.primary(), false,
                                row.allocationPercent(), row.displayOrder(), row.attributes(), null, null)));
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

    // A row with an ID changes that assignment. An external ID beside it must be one of that assignment.
    private String assignmentById(AssignmentRow row, String source) {
        if (people.assignments().rows(row.id()).isEmpty()) {
            throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.id()));
        }
        requireNames(ExternalId.Kind.ASSIGNMENT, row.id(), source, row.externalId());
        return row.id();
    }

    // A row of a stored assignment that names no employment keeps the stored one.
    private String employmentId(AssignmentRow row, String source, Optional<Assignment> stored) {
        if (row.employmentExternalId() != null && row.employmentId() != null) {
            throw FuhitoException.invalid("import.employment_twice",
                    Map.of("employmentExternalId", row.employmentExternalId(), "employmentId", row.employmentId()));
        }
        if (row.employmentId() != null) {
            if (people.employments().find(row.employmentId()).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.employmentId()));
            }
            return row.employmentId();
        }
        if (row.employmentExternalId() == null && stored.isPresent()) {
            return stored.get().employmentId();
        }
        String externalId = Checks.required("employmentExternalId", row.employmentExternalId());
        return externalIds.resourceOf(ExternalId.Kind.EMPLOYMENT, source, externalId)
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", externalId)));
    }

    // A row of a stored assignment that names no org unit keeps the stored one.
    private String orgUnitId(AssignmentRow row, String source, Optional<Assignment> stored) {
        if (row.orgUnitExternalId() != null && row.orgUnitId() != null) {
            throw FuhitoException.invalid("import.org_unit_twice",
                    Map.of("orgUnitExternalId", row.orgUnitExternalId(), "orgUnitId", row.orgUnitId()));
        }
        if (row.orgUnitId() != null) {
            if (orgUnits.findUnit(row.orgUnitId()).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.orgUnitId()));
            }
            return row.orgUnitId();
        }
        if (row.orgUnitExternalId() == null && stored.isPresent()) {
            return stored.get().orgUnitId();
        }
        String externalId = Checks.required("orgUnitExternalId", row.orgUnitExternalId());
        return externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, externalId)
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", externalId)));
    }

    private static boolean sameDetails(AssignmentRow row, AssignmentView.Row current) {
        Assignment stored = current.row();
        return Objects.equals(row.jobTitle(), current.jobTitle())
                && Objects.equals(row.occupation(), current.occupation())
                && Objects.equals(row.kind(), current.kind())
                && Objects.equals(row.workLocation(), current.places().workLocation())
                && Objects.equals(row.costCenter(), current.places().costCenter())
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

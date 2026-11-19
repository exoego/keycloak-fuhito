package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The list of employments of one document. The writes of the list come into the report in the order of its rows,
 * once the new employments are stored.
 */
final class EmploymentImport {

    private static final String EMPLOYMENTS = "employments";

    private final PeopleImport document;
    private final ListIds ids;
    // The rows change no org unit, so the employer of a date is the same for all of them.
    private final EmploymentService service;
    private final EmploymentService.NewEmployments started;
    private final Map<String, Optional<EmploymentView>> stored = new HashMap<>();
    private final Map<String, Boolean> units = new HashMap<>();
    private final List<Supplier<Write>> writes = new ArrayList<>();
    private final Map<String, EmploymentView> views = new HashMap<>();

    EmploymentImport(PeopleImport document) {
        this.document = document;
        this.ids = new ListIds(document.externalIds);
        this.service = document.people.employmentService().remembering();
        this.started = service.newEmployments(
                (source, value) -> ids.resourceOf(ExternalId.Kind.EMPLOYMENT, source, value));
    }

    void run(List<EmploymentRow> rows) {
        document.report.startList(EMPLOYMENTS);
        read(rows);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                document.report.count(EMPLOYMENTS, apply(Checks.required("row", rows.get(i)), seen));
            } catch (FuhitoException e) {
                document.report.error(EMPLOYMENTS, i, e);
            }
            document.rowDone.run();
        }
        started.insert();
        views.putAll(started.views());
        writes.forEach(write -> document.report.write(write.get()));
        document.rowDone.run();
    }

    // The stored employments that the rows name. The people and the staff numbers of the new ones.
    private void read(List<EmploymentRow> rows) {
        List<EmploymentRow> given = rows.stream().filter(Objects::nonNull).toList();
        for (EmploymentRow row : given) {
            String source = document.sourceOf(row.source());
            ids.want(ExternalId.Kind.EMPLOYMENT, source, row.externalId())
                    .want(ExternalId.Kind.EMPLOYMENT, source, row.continuesEmploymentExternalId())
                    .want(ExternalId.Kind.PERSON, source, row.personExternalId())
                    .want(ExternalId.Kind.ORG_UNIT, source, row.employerExternalId());
        }
        ids.read();
        List<Set<String>> named = given.stream()
                .map(row -> document.named(ids, row.id(), ExternalId.Kind.EMPLOYMENT, row.source(), row.externalId()))
                .toList();
        Set<String> employmentIds = new HashSet<>();
        named.forEach(employmentIds::addAll);
        Map<String, EmploymentView> found = service.getAll(employmentIds);
        employmentIds.forEach(id -> stored.put(id, Optional.ofNullable(found.get(id))));
        Set<String> personIds = new HashSet<>();
        Set<String> staffNumbers = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            EmploymentRow row = given.get(i);
            if (row.personId() != null) {
                personIds.add(row.personId());
            }
            if (named.get(i).stream().noneMatch(found::containsKey)) {
                personIds.addAll(document.named(ids, null, ExternalId.Kind.PERSON, row.source(),
                        row.personExternalId()));
                if (row.staffNumber() != null) {
                    staffNumbers.add(row.staffNumber());
                }
            }
        }
        started.read(personIds, staffNumbers);
    }

    private Optional<EmploymentView> find(String id) {
        return stored.computeIfAbsent(id, key -> document.people.employments().find(key).map(e -> service.get(key)));
    }

    private Outcome apply(EmploymentRow row, Set<String> seen) {
        document.requireFirst(seen, row.source(), row.externalId(), row.id());
        String source = Checks.code("source", row.source() == null ? document.defaultSource : row.source());
        String name = row.externalId() == null ? row.id() : row.externalId();
        Optional<EmploymentView> before = row.id() == null
                ? ids.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.externalId()).flatMap(this::find)
                : Optional.of(byId(row, source));
        String personId = personId(row, name, source, before);
        String employerId = employerId(row, source);
        String continues = continued(row, source);
        if (before.isEmpty()) {
            NewEmployment request = new NewEmployment(personId,
                    row.validFrom() == null ? document.effectiveDate : row.validFrom(), row.validTo(),
                    row.staffNumber(), row.employmentType(), row.grade(), row.status(), row.leaveReason(),
                    row.workPercent(), row.contractEndDate(), row.attributes(), row.externalId(), source, employerId);
            String id = ids.create(ExternalId.Kind.EMPLOYMENT, source, row.externalId(),
                    () -> started.start(request, continues), Employment::id).id();
            boolean ended = row.validTo() != null && row.endReason() != null;
            // The new employment has no end reason until the end that the row gives.
            writes.add(() -> new Write.OfEmployment(true, "", ended ? withoutEndReason(views.get(id)) : views.get(id)));
            if (ended) {
                started.endReason(id, row.endReason());
                writes.add(() -> new Write.OfEmployment(false, "/end", views.get(id)));
            }
            return Outcome.CREATED;
        }
        return change(row, name, before.get(), personId, employerId, continues);
    }

    private Outcome change(EmploymentRow row, String name, EmploymentView before, String personId, String employerId,
            String continues) {
        Employment employment = before.employment();
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
        ImportMatch.start(id, employment.period().from(), row.validFrom());
        boolean changed = false;
        // An employment that ended by the effective date has no terms to compare. One that starts later can only
        // come again as it is, for example in the document that made it.
        LocalDate date = Checks.date("effectiveDate", document.effectiveDate);
        boolean endsBefore = employment.period().to() != null && !employment.period().to().isAfter(date);
        if (!endsBefore) {
            boolean later = employment.period().from().isAfter(date);
            LocalDate on = later ? employment.period().from() : date;
            EmploymentView.TermsView terms = before.terms().stream()
                    .filter(t -> t.terms().period().contains(on))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", document.effectiveDate)));
            if (!sameTerms(row, terms)) {
                if (later) {
                    throw FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", document.effectiveDate));
                }
                written("/terms", id, () -> service.changeTerms(id, new TermsChange(document.effectiveDate,
                        row.employmentType(), row.grade(), row.status(), row.leaveReason(), row.workPercent(),
                        row.contractEndDate(), row.attributes())));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(employment.period().to(), null);
        boolean sameEnd = Objects.equals(row.validTo(), storedEnd)
                && (row.endReason() == null || row.endReason().equals(before.endReason()));
        if (row.validTo() != null && !sameEnd) {
            written("/end", id, () -> service.end(id, row.validTo(), row.endReason()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private void written(String suffix, String id, Supplier<EmploymentView> write) {
        started.insert();
        EmploymentView after = write.get();
        stored.remove(id);
        writes.add(() -> new Write.OfEmployment(false, suffix, after));
    }

    private static EmploymentView withoutEndReason(EmploymentView view) {
        Employment e = view.employment();
        return new EmploymentView(new Employment(e.id(), e.personId(), e.userId(), e.employerId(), e.period(),
                e.staffNumber(), null, e.source(), e.continuesEmploymentId(), e.version()), null, view.terms());
    }

    // A row with an ID changes that employment. An external ID beside it must be one of that employment.
    private EmploymentView byId(EmploymentRow row, String source) {
        EmploymentView view = find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        PeopleImport.requireNames(ids, ExternalId.Kind.EMPLOYMENT, row.id(), source, row.externalId());
        return view;
    }

    private String employerId(EmploymentRow row, String source) {
        if (row.employerExternalId() != null && row.employerId() != null) {
            throw FuhitoException.invalid("import.employer_twice",
                    Map.of("employerExternalId", row.employerExternalId(), "employerId", row.employerId()));
        }
        if (row.employerId() != null) {
            if (!units.computeIfAbsent(row.employerId(), id -> document.orgUnits.findUnit(id).isPresent())) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.employerId()));
            }
            return row.employerId();
        }
        return row.employerExternalId() == null ? null
                : ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.employerExternalId())
                        .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                                Map.of("orgUnitExternalId", row.employerExternalId())));
    }

    private String continued(EmploymentRow row, String source) {
        if (row.continuesEmploymentExternalId() != null && row.continuesEmploymentId() != null) {
            throw FuhitoException.invalid("import.continues_twice", Map.of());
        }
        if (row.continuesEmploymentExternalId() == null) {
            return row.continuesEmploymentId();
        }
        return ids.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.continuesEmploymentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", row.continuesEmploymentExternalId())));
    }

    // A row names a person of the list of people, or a Keycloak user. The person of a user is used, and created
    // when the user has none. A row for a stored employment may name nobody.
    private String personId(EmploymentRow row, String name, String source, Optional<EmploymentView> before) {
        boolean namesUser = row.userId() != null || row.username() != null;
        if (row.personExternalId() != null && row.personId() != null) {
            throw FuhitoException.invalid("import.person_twice",
                    Map.of("personExternalId", row.personExternalId(), "personId", row.personId()));
        }
        if (row.personExternalId() != null || row.personId() != null) {
            String id = row.personId() != null
                    ? started.person(row.personId()).map(Person::id)
                            .orElseThrow(() -> FuhitoException.invalid("import.id_unknown",
                                    Map.of("id", row.personId())))
                    : ids.resourceOf(ExternalId.Kind.PERSON, source, row.personExternalId())
                            .orElseThrow(() -> FuhitoException.invalid("import.person_unknown",
                                    Map.of("personExternalId", row.personExternalId())));
            if (namesUser && !document.userId(row.userId(), row.username())
                    .equals(started.person(id).map(Person::userId).orElse(null))) {
                throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", name));
            }
            return id;
        }
        if (!namesUser && before.isPresent()) {
            return before.get().employment().personId();
        }
        PersonService.Resolved resolved = document.people.personService()
                .forUser(document.userId(row.userId(), row.username()));
        if (resolved.created() != null) {
            Write person = new Write.OfPerson(resolved.created());
            writes.add(() -> person);
        }
        return resolved.person().id();
    }

    private static boolean sameTerms(EmploymentRow row, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = row.status() == null ? "ACTIVE" : row.status();
        return Objects.equals(row.employmentType(), terms.employmentType())
                && Objects.equals(row.grade(), terms.grade())
                && status.equals(stored.status().name())
                && Objects.equals(row.leaveReason(), terms.leaveReason())
                && PeopleImport.sameNumber(row.workPercent(), stored.workPercent())
                && Objects.equals(row.contractEndDate(), Objects.toString(stored.contractEndDate(), null))
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(stored.attributes());
    }
}

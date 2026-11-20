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
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
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
    // The new employments that continue another, by row. The rows that set an end, by employment.
    private final Map<Integer, Employment> continuing = new HashMap<>();
    private final Map<String, Integer> ends = new HashMap<>();

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
        Map<Integer, Outcome> outcomes = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                outcomes.put(i, apply(EmptyValues.checked(rows.get(i)), i, seen));
            } catch (FuhitoException e) {
                document.report.error(EMPLOYMENTS, i, e);
            }
            document.rowDone.run();
        }
        started.insert();
        unended().forEach((row, e) -> {
            outcomes.remove(row);
            document.report.error(EMPLOYMENTS, row, e);
        });
        outcomes.values().forEach(outcome -> document.report.count(EMPLOYMENTS, outcome));
        views.putAll(started.views());
        writes.forEach(write -> document.report.write(write.get()));
        document.rowDone.run();
    }

    // The stored employments that the rows name. The people and the staff numbers of the new ones.
    private void read(List<EmploymentRow> rows) {
        List<EmploymentRow> given = rows.stream().filter(EmptyValues::passes).toList();
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

    private Outcome apply(EmploymentRow row, int index, Set<String> seen) {
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
            requireEnd(row.validTo(), row.endReason());
            NewEmployment request = new NewEmployment(personId,
                    row.validFrom() == null ? document.effectiveDate : row.validFrom(), row.validTo(),
                    row.staffNumber(), row.employmentType(), row.grade(), row.status(), row.leaveReason(),
                    row.workPercent(), row.contractEndDate(), Merged.attributes(row, row.attributes(), null),
                    row.externalId(), source, employerId, row.externalEmployer());
            Employment employment = ids.create(ExternalId.Kind.EMPLOYMENT, source, row.externalId(),
                    () -> started.start(request, continues), Employment::id);
            String id = employment.id();
            boolean ended = row.endReason() != null;
            // The new employment has no end reason until the end that the row gives.
            writes.add(() -> new Write.OfEmployment(true, "", ended ? withoutEndReason(views.get(id)) : views.get(id)));
            if (ended) {
                started.endReason(id, row.endReason());
                writes.add(() -> new Write.OfEmployment(false, "/end", views.get(id)));
            }
            if (continues != null) {
                continuing.put(index, employment);
            }
            return Outcome.CREATED;
        }
        return change(row, index, name, before.get(), personId, employerId, continues);
    }

    private Outcome change(EmploymentRow row, int index, String name, EmploymentView before, String personId,
            String employerId, String continues) {
        Employment employment = before.employment();
        String id = employment.id();
        if (!employment.personId().equals(personId)) {
            throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", name));
        }
        // The employer never changes. A transfer to another employer is the way to move a person. The code of an
        // external employer is compared, so a row may name an item that is no longer active. Null names none.
        boolean givesEmployer = row.gives("employerExternalId", row.employerExternalId())
                || row.gives("employerId", row.employerId());
        if ((givesEmployer && !Objects.equals(employerId, employment.employer().legalEntityId()))
                || (row.gives("externalEmployer", row.externalEmployer())
                        && !Objects.equals(row.externalEmployer(), before.externalEmployer()))) {
            throw FuhitoException.conflict("import.employer_mismatch", Map.of("externalId", name));
        }
        boolean givesContinued = row.gives("continuesEmploymentExternalId", row.continuesEmploymentExternalId())
                || row.gives("continuesEmploymentId", row.continuesEmploymentId());
        if (givesContinued && !Objects.equals(continues, employment.continuesEmploymentId())) {
            throw FuhitoException.conflict("import.continues_mismatch", Map.of("externalId", name));
        }
        ImportMatch.start(row, id, employment.period().from(), row.validFrom());
        String storedEnd = Objects.toString(employment.period().to(), null);
        String validTo = row.or("validTo", row.validTo(), storedEnd);
        requireEnd(validTo, row.endReason());
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
            TermsChange wanted = terms(row, terms);
            if (!sameTerms(wanted, terms)) {
                if (later) {
                    throw FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", document.effectiveDate));
                }
                written("/terms", id, () -> service.changeTerms(id, wanted));
                changed = true;
            }
        }
        String endReason = row.or("endReason", row.endReason(), before.endReason());
        if (validTo == null && storedEnd != null) {
            written("/end", id, () -> service.removeEnd(id));
            changed = true;
        } else if (validTo != null && (!validTo.equals(storedEnd) || !Objects.equals(endReason, before.endReason()))) {
            written("/end", id, () -> service.end(id, validTo, endReason, false));
            ends.put(id, index);
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    /**
     * Requires that only an employment with an end has an end reason. A row that removes the end and gives no reason
     * removes the stored one with the end.
     *
     * @param validTo the end of the employment, or null
     * @param endReason the end reason, or null
     * @throws FuhitoException when the employment has an end reason and no end
     */
    static void requireEnd(String validTo, String endReason) {
        if (validTo == null && endReason != null) {
            throw FuhitoException.invalid("employment.end_reason_without_end", Map.of("endReason", endReason));
        }
    }

    // A status that is not a leave drops the stored reason, unless the row gives one.
    private TermsChange terms(EmploymentRow row, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = row.or("status", row.status(), stored.status().name());
        boolean hasReason = row.gives("leaveReason", row.leaveReason())
                || EmploymentStatus.ON_LEAVE.name().equals(status);
        String leaveReason = hasReason ? row.or("leaveReason", row.leaveReason(), terms.leaveReason()) : null;
        return new TermsChange(document.effectiveDate,
                row.or("employmentType", row.employmentType(), terms.employmentType()),
                row.or("grade", row.grade(), terms.grade()), status, leaveReason,
                row.or("workPercent", row.workPercent(), stored.workPercent()),
                row.or("contractEndDate", row.contractEndDate(), Objects.toString(stored.contractEndDate(), null)),
                Merged.attributes(row, row.attributes(), stored.attributes()));
    }

    // An employment that another one continues must end by the start of that one. The row that ends it may come
    // after the row of the new one, so the check looks at what the whole list made. It also keeps a later end from
    // opening such an employment again.
    private Map<Integer, FuhitoException> unended() {
        if (continuing.isEmpty() && ends.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = new HashSet<>(ends.keySet());
        continuing.values().forEach(next -> ids.add(next.continuesEmploymentId()));
        Map<String, Employment> found = new HashMap<>();
        document.people.employments().findAll(ids).forEach(employment -> found.put(employment.id(), employment));
        Map<Integer, FuhitoException> failed = new TreeMap<>();
        continuing.forEach((row, next) -> holds(found, next).ifPresent(e -> failed.put(row, e)));
        if (ends.isEmpty()) {
            return failed;
        }
        // A new one is checked on its own row.
        Set<String> newIds = continuing.values().stream().map(Employment::id).collect(Collectors.toSet());
        for (Employment next : service.continuing(ends.keySet().stream().map(found::get).toList())) {
            if (!newIds.contains(next.id())) {
                holds(found, next).ifPresent(e -> failed.putIfAbsent(ends.get(next.continuesEmploymentId()), e));
            }
        }
        return failed;
    }

    private static Optional<FuhitoException> holds(Map<String, Employment> found, Employment next) {
        Employment previous = found.get(next.continuesEmploymentId());
        return EmploymentService.stillHolds(previous.id(), previous.period(), next.period().from());
    }

    private void written(String suffix, String id, Supplier<EmploymentView> write) {
        started.insert();
        EmploymentView after = write.get();
        stored.remove(id);
        writes.add(() -> new Write.OfEmployment(false, suffix, after));
    }

    private static EmploymentView withoutEndReason(EmploymentView view) {
        Employment e = view.employment();
        return new EmploymentView(new Employment(e.id(), e.personId(), e.userId(), e.employer(), e.period(),
                e.staffNumber(), null, e.source(), e.continuesEmploymentId(), e.version()), view.externalEmployer(),
                null, view.terms());
    }

    // A row with an ID changes that employment. An external ID beside it must be one of that employment.
    private EmploymentView byId(EmploymentRow row, String source) {
        EmploymentView view = find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        ImportMatch.source(row.id(), view.employment().source(), row.source(), row.externalId(),
                row.personExternalId(), row.employerExternalId(), row.continuesEmploymentExternalId());
        PeopleImport.requireNames(ids, ExternalId.Kind.EMPLOYMENT, row.id(), source, row.externalId());
        return view;
    }

    // The ID of the legal entity that a row names. The rule for a new employment checks the external employer.
    private String employerId(EmploymentRow row, String source) {
        if (row.employerExternalId() != null && row.employerId() != null) {
            throw FuhitoException.invalid("import.employer_twice",
                    Map.of("employerExternalId", row.employerExternalId(), "employerId", row.employerId()));
        }
        if (row.externalEmployer() != null && (row.employerExternalId() != null || row.employerId() != null)) {
            throw FuhitoException.invalid("employment.employer_twice",
                    Map.of("externalEmployer", row.externalEmployer()));
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
        boolean givesPerson = row.gives("userId", row.userId()) || row.gives("username", row.username())
                || row.gives("personExternalId", row.personExternalId()) || row.gives("personId", row.personId());
        if (!givesPerson && before.isPresent()) {
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

    private static boolean sameTerms(TermsChange wanted, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = wanted.status() == null ? "ACTIVE" : wanted.status();
        return Objects.equals(wanted.employmentType(), terms.employmentType())
                && Objects.equals(wanted.grade(), terms.grade())
                && status.equals(stored.status().name())
                && Objects.equals(wanted.leaveReason(), terms.leaveReason())
                && PeopleImport.sameNumber(wanted.workPercent(), stored.workPercent())
                && Objects.equals(wanted.contractEndDate(), Objects.toString(stored.contractEndDate(), null))
                && (wanted.attributes() == null ? Map.of() : wanted.attributes()).equals(stored.attributes());
    }
}

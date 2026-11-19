package net.exoego.keycloak.fuhito.service;

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
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.service.ImportDocument.QualificationRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The qualifications of one document. A row is matched by source and external ID, or by ID. A qualification is a
 * record with dates of its own, so a row replaces the stored one as {@code PUT} does.
 */
final class QualificationImport {

    static final String QUALIFICATIONS = "qualifications";

    private final QualificationService qualifications;
    private final ImportService.People people;
    private final ExternalIdService externalIds;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();

    QualificationImport(QualificationService qualifications, ImportService.People people,
            ExternalIdService externalIds, String effectiveDate, String defaultSource, ImportReport report) {
        this.qualifications = qualifications;
        this.people = people;
        this.externalIds = externalIds;
        this.effectiveDate = Checks.date("effectiveDate", effectiveDate).toString();
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<QualificationRow> rows) {
        report.startList(QUALIFICATIONS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(QUALIFICATIONS, apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(QUALIFICATIONS, i, e);
            }
        }
    }

    private Outcome apply(QualificationRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        if (row.externalId() == null && row.id() == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "externalId"));
        }
        if (!seen.add(row.id() == null ? source + "\u0000" + row.externalId() : "\u0000id\u0000" + row.id())) {
            throw FuhitoException.invalid("import.duplicate_row",
                    Map.of("externalId", Objects.requireNonNullElse(row.externalId(), row.id())));
        }
        Optional<QualificationView> stored = stored(row, source);
        String personId = personId(row, source, stored);
        String validFrom = row.validFrom() != null ? row.validFrom()
                : stored.map(view -> view.qualification().period().from().toString()).orElse(effectiveDate);
        QualificationValues values = new QualificationValues(personId, row.kind(), row.number(), validFrom,
                row.validTo(), row.attributes(), row.externalId(), source);
        if (stored.isEmpty()) {
            report.write(new Write.OfQualification(true, qualifications.add(values)));
            return Outcome.CREATED;
        }
        Qualification before = stored.get().qualification();
        if (!before.personId().equals(personId)) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", before.id(), "externalId", personId));
        }
        boolean same = Objects.equals(stored.get().kind(), row.kind())
                && Objects.equals(before.number(), Checks.optionalName("number", row.number()))
                && before.period().from().toString().equals(values.validFrom())
                && Objects.equals(Objects.toString(before.period().to(), null), row.validTo())
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(before.attributes());
        if (same) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfQualification(false, qualifications.replace(before.id(), values)));
        return Outcome.CHANGED;
    }

    // A row with an ID changes that qualification. An external ID beside it must be one of it.
    private Optional<QualificationView> stored(QualificationRow row, String source) {
        if (row.id() == null) {
            return externalIds.resourceOf(ExternalId.Kind.QUALIFICATION, source, row.externalId())
                    .flatMap(qualifications::find);
        }
        QualificationView view = qualifications.find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        if (row.externalId() != null && !externalIds.resourceOf(ExternalId.Kind.QUALIFICATION, source,
                row.externalId()).equals(Optional.of(row.id()))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", row.externalId()));
        }
        return Optional.of(view);
    }

    // A row of a stored qualification may name nobody. A user who has no person yet gets one.
    private String personId(QualificationRow row, String source, Optional<QualificationView> stored) {
        if (Stream.of(row.personExternalId(), row.personId(), row.username()).filter(Objects::nonNull).count() > 1) {
            throw FuhitoException.invalid("import.person_ways", Map.of());
        }
        if (row.personId() != null) {
            if (people.personService().find(row.personId()).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.personId()));
            }
            return row.personId();
        }
        if (row.personExternalId() != null) {
            return externalIds.resourceOf(ExternalId.Kind.PERSON, source, row.personExternalId())
                    .orElseThrow(() -> FuhitoException.invalid("import.person_unknown",
                            Map.of("personExternalId", row.personExternalId())));
        }
        if (row.username() != null) {
            String userId = people.users().findIdByUsername(row.username()).orElseThrow(() ->
                    FuhitoException.invalid("import.username_unknown", Map.of("username", row.username())));
            PersonService.Resolved resolved = people.personService().forUser(userId);
            if (resolved.created() != null) {
                report.write(new Write.OfPerson(resolved.created()));
            }
            return resolved.person().id();
        }
        return stored.map(view -> view.qualification().personId()).orElseThrow(() -> FuhitoException.invalid(
                "invalid.required", Map.of("field", "personExternalId")));
    }
}

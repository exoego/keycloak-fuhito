package net.exoego.keycloak.fuhito.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.service.ImportDocument.QualificationRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The qualifications of one document. A row is matched by source and external ID, or by ID. A row of a stored one
 * changes the values that it gives.
 */
final class QualificationImport {

    static final String QUALIFICATIONS = "qualifications";

    private final QualificationService qualifications;
    private final AttributeRules rules;
    private final ImportService.People people;
    private final ExternalIdService externalIds;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();
    private final Runnable rowDone;

    QualificationImport(QualificationService qualifications, ImportService.People people,
            ExternalIdService externalIds, String effectiveDate, String defaultSource, ImportReport report,
            Runnable rowDone) {
        this.rowDone = rowDone;
        this.qualifications = qualifications;
        this.rules = qualifications.attributeRules().remembering();
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
                report.count(QUALIFICATIONS, apply(EmptyValues.checked(rows.get(i))));
            } catch (FuhitoException e) {
                report.error(QUALIFICATIONS, i, e);
            }
            rowDone.run();
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
        if (stored.isEmpty()) {
            report.write(new Write.OfQualification(true, qualifications.add(new QualificationValues(personId,
                    row.kind(), row.number(), row.validFrom() == null ? effectiveDate : row.validFrom(),
                    row.validTo(), Merged.attributes(row, row.attributes(), null, rules, Target.QUALIFICATION),
                    row.externalId(), source))));
            return Outcome.CREATED;
        }
        Qualification before = stored.get().qualification();
        if (!before.personId().equals(personId)) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", before.id(), "externalId", personId));
        }
        if (row.isNull("validFrom")) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "validFrom"));
        }
        QualificationValues values = new QualificationValues(personId,
                row.or("kind", row.kind(), stored.get().kind()), row.or("number", row.number(), before.number()),
                row.or("validFrom", row.validFrom(), before.period().from().toString()),
                row.or("validTo", row.validTo(), Objects.toString(before.period().to(), null)),
                Merged.attributes(row, row.attributes(), before.attributes(), rules, Target.QUALIFICATION),
                row.externalId(), source);
        boolean same = Objects.equals(stored.get().kind(), values.kind())
                && Objects.equals(before.number(), Checks.optionalName("number", values.number()))
                && before.period().from().toString().equals(values.validFrom())
                && Objects.equals(Objects.toString(before.period().to(), null), values.validTo())
                && values.attributes().equals(before.attributes());
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
        ImportMatch.source(row.id(), view.qualification().source(), row.source(), row.externalId(),
                row.personExternalId());
        if (row.externalId() != null && !externalIds.resourceOf(ExternalId.Kind.QUALIFICATION, source,
                row.externalId()).equals(Optional.of(row.id()))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", row.externalId()));
        }
        return Optional.of(view);
    }

    // A row of a stored qualification may leave out the person. A user who has no person yet gets one.
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
        boolean cleared = row.isNull("personExternalId") || row.isNull("personId") || row.isNull("username");
        return stored.filter(view -> !cleared).map(view -> view.qualification().personId()).orElseThrow(
                () -> FuhitoException.invalid("invalid.required", Map.of("field", "personExternalId")));
    }
}

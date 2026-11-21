package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.domain.ReportingLine;

/**
 * Checks an export before a restore writes it. A row may name only rows of the same export, so that a restore
 * never links to the rows of another realm, and fails with a clear error and not in the database. A row has no empty
 * value and no text that cannot be stored, as a row of an import, each of its texts fits its column, each of its
 * choices and names is one that a write takes, and its period ends after it starts. A write stores some values in one
 * form, such as a name with no spaces around it, and a restore stores a value as the export has it. So such a value
 * must be in that form already. A time must be in the form that an export gives, because a restore stores the moment
 * and not the text.
 */
final class RestoreCheck {

    // Any rule will do for the check of the time settings of a unit.
    private static final BusinessDay.Rule ANY_DAY = new BusinessDay.Rule(ZoneOffset.UTC, LocalTime.MIDNIGHT);

    private static final Set<String> DATES = Set.of("validFrom", "validTo", "effectiveDate", "orderDate",
            "contractEndDate");

    // A time must be on a date that a write takes, in UTC.
    private static final Set<String> TIMES = Set.of("createdAt", "announcedAt", "maskedAt", "erasedAt");

    private final Set<String> refItems;
    private final Set<String> orgUnits;
    private final Set<String> people;
    private final Set<String> employments;
    private final Set<String> assignments;
    private final Set<String> qualifications;
    private final Set<String> lines;
    private final Set<String> roles;
    private final Set<String> orders;
    private final Set<String> refKinds;
    private final Set<String> hierarchies;
    private final Set<String> hierarchyCodes;
    private final Set<String> definitions;

    private RestoreCheck(RealmExport document) {
        refItems = ids(document.refItems(), RealmExport.Item::id);
        orgUnits = ids(document.orgUnits(), RealmExport.Unit::id);
        people = ids(document.people(), RealmExport.PersonRow::id);
        employments = ids(document.employments(), RealmExport.EmploymentRow::id);
        assignments = ids(document.assignments(), RealmExport.AssignmentRows::assignmentId);
        qualifications = ids(document.qualifications(), RealmExport.QualificationRow::id);
        lines = ids(document.reportingLines(), RealmExport.Line::id);
        roles = ids(document.unitRoles(), RealmExport.Role::id);
        orders = ids(document.changeOrders(), RealmExport.Order::id);
        refKinds = ids(document.refKinds(), RealmExport.Kind::id);
        hierarchies = ids(document.hierarchies(), RealmExport.HierarchyRow::id);
        // Every realm has the default hierarchy, so a link may name it with no row.
        hierarchyCodes = ids(document.hierarchies(), RealmExport.HierarchyRow::code);
        hierarchyCodes.add(Hierarchy.DEFAULT_CODE);
        definitions = ids(document.attributeDefinitions(), RealmExport.Definition::id);
    }

    /**
     * Requires every row of an export and every value that the table of a row needs, and requires that no value is an
     * empty text, a text of only spaces, an empty list, or a text that cannot be stored, as in a row of an import. A
     * text must fit its column, and a value that a write checks by a rule of its own, such as a code or a date, must
     * pass the rule. A choice, such as the status of terms, and the names of a row must be ones that a write takes.
     * A value that a write would store in another form, such as a name with spaces around it or the language tag
     * {@code JA} for {@code ja}, fails too. So does a time in another form than an export gives, such as a time with
     * an offset. A version must be from 1 to {@link RealmExport#LARGEST_VERSION}, so that a write can count it up.
     * The {@code validTo} of a row must be after its {@code validFrom}, as in a write. A row inside a row,
     * such as a version of an org unit, is named by the list and the ID that {@code restore.id_taken} gives it.
     *
     * @param document the export
     * @param lengths gives the longest text of each field of a row of a list
     * @throws FuhitoException when a row or a needed value is missing, a value is empty, a text cannot be stored or
     *         is too long for its column, a value breaks its rule or is not in the form that a write stores, a time
     *         is not in the form that an export gives, a version is outside its range, a choice is unknown, the names
     *         break a rule of a write, or a period does not end after it starts. The error names the list, the ID of the row when it has one, and
     *         the field. For a choice and the names, {@code rule} names the error of the write.
     */
    static void requireValues(RealmExport document, Function<String, Map<String, Integer>> lengths) {
        Values values = new Values(lengths);
        values.check("ref-kinds", document.refKinds(), "id", "code");
        values.check("ref-items", document.refItems(), "id", "kind", "code", "attributes");
        all(document.refItems()).forEach(i -> values.check("ref-item-names", i.versions(), "id"));
        values.check("attribute-definitions", document.attributeDefinitions(), "id", "target", "name", "type");
        values.check("hierarchies", document.hierarchies(), "id", "code", "name");
        values.check("org-units", document.orgUnits(), "id", "parents", "successors");
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            values.check("org-unit-versions", unit.versions(), "id", "validFrom", "attributes");
            values.check("org-unit-parents", unit.parents(), "id", "hierarchy", "parentId", "validFrom");
            values.check("org-unit-successions", unit.successors(), "id", "successorId", "effectiveDate");
        }
        values.check("people", document.people(), "id", "attributes");
        values.check("employments", document.employments(), "id", "personId", "validFrom");
        all(document.employments()).forEach(e -> values.check("employment-terms", e.terms(), "id", "validFrom",
                "status", "attributes"));
        values.check("assignments", document.assignments(), "assignmentId");
        all(document.assignments()).forEach(a -> values.check("assignments", a.periods(), "rowId", "employmentId",
                "orgUnitId", "validFrom", "attributes"));
        values.check("reporting-lines", document.reportingLines(), "id", "assignmentId", "managerPersonId", "kind",
                "validFrom");
        values.check("unit-roles", document.unitRoles(), "id", "employmentId", "orgUnitId", "roleId", "validFrom");
        values.check("qualifications", document.qualifications(), "id", "personId", "kindId", "validFrom",
                "attributes");
        values.check("external-ids", document.externalIds(), "id", "resourceType", "resourceId", "source",
                "externalId");
        values.check("change-orders", document.changeOrders(), "id", "title", "effectiveDate", "createdAt");
        values.check("erasures", document.erasures(), "personId", "erasedAt");
        requireNames(document);
    }

    private static void requireNames(RealmExport document) {
        all(document.refKinds()).forEach(k -> names("ref-kinds", k.id(), k.primaryLanguage(), k.names(),
                () -> RefKindName.check(k.primaryLanguage(), k.names())));
        for (RealmExport.Item item : all(document.refItems())) {
            all(item.versions()).forEach(n -> names("ref-item-names", n.id(), n.primaryLanguage(), n.names(),
                    () -> RefItemName.check(n.primaryLanguage(), n.names())));
        }
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            all(unit.versions()).forEach(v -> names("org-unit-versions", v.id(), v.primaryLanguage(), v.names(),
                    () -> Localized.check(v.primaryLanguage(), v.names(), (field, name) -> name.checked(field))));
        }
        all(document.people()).forEach(p -> names("people", p.id(), p.primaryLanguage(), p.names(),
                () -> PersonName.check(p.primaryLanguage(), p.names())));
    }

    // Only invalid.primary_language names no field, and it is about primaryLanguage. A write stores the names that
    // the check gives back. A person may have none.
    private static <T extends Record> void names(String list, String id, String primaryLanguage, Map<String, T> names,
            Supplier<Localized<T>> check) {
        Localized<T> stored;
        try {
            stored = check.get();
        } catch (FuhitoException e) {
            throw RestoreRules.broken(list, id, e.params().getOrDefault("field", "primaryLanguage"), e.code())
                    .causedBy(e);
        }
        if (stored == null) {
            return;
        }
        if (!stored.primaryLanguage().equals(primaryLanguage)) {
            throw RestoreRules.broken(list, id, "primaryLanguage", null);
        }
        names.forEach((tag, name) -> {
            T same = stored.byLanguage().get(tag);
            if (same == null) {
                throw RestoreRules.broken(list, id, "names." + tag, null);
            }
            for (RecordComponent component : name.getClass().getRecordComponents()) {
                if (!Objects.equals(EmptyValues.valueOf(component, name), EmptyValues.valueOf(component, same))) {
                    throw RestoreRules.broken(list, id, "names." + tag + "." + component.getName(), null);
                }
            }
        });
    }

    private record Values(Function<String, Map<String, Integer>> lengths) {

        // The needed fields have columns that are not null, or are lists that an export always gives, and the first
        // one is the ID of the row. A list in a row holds the rows of the row, and a row may have none. Each of them
        // is checked as a row.
        <T extends Record> void check(String list, List<T> rows, String... needed) {
            Map<String, Integer> longest = lengths.apply(list);
            for (T row : all(rows)) {
                if (row == null) {
                    throw FuhitoException.invalid("restore.bad_value", Map.of("list", list));
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                for (RecordComponent component : row.getClass().getRecordComponents()) {
                    fields.put(component.getName(), EmptyValues.valueOf(component, row));
                }
                Map<String, String> params = new HashMap<>(Map.of("list", list));
                Optional.ofNullable(fields.get(needed[0])).ifPresent(id -> params.put("id", id.toString()));
                for (String field : needed) {
                    if (fields.get(field) == null) {
                        params.put("field", field);
                        throw FuhitoException.invalid("restore.bad_value", params);
                    }
                }
                try {
                    EmptyValues.withoutEmptyValues(row, component -> component.getType() != List.class);
                } catch (FuhitoException e) {
                    Map<String, String> named = new HashMap<>(e.params());
                    named.putAll(params);
                    throw new FuhitoException(e.reason(), e.code(), named).causedBy(e);
                }
                fields.forEach((field, value) -> {
                    requireFit(params, field, value, longest.get(field));
                    if (value instanceof String text) {
                        RestoreRules.check(list, params.get("id"), field, () -> choice(list, field, text));
                    }
                });
                requireOrder(params, (String) fields.get("validFrom"), (String) fields.get("validTo"));
            }
        }
    }

    // Names with no start hold from the start, so any end fits them.
    private static void requireOrder(Map<String, String> params, String from, String to) {
        if (from == null || to == null) {
            return;
        }
        try {
            Checks.period(LocalDate.parse(from), LocalDate.parse(to));
        } catch (FuhitoException e) {
            Map<String, String> named = new HashMap<>(params);
            named.put("field", "validTo");
            throw FuhitoException.invalid("restore.bad_value", named).causedBy(e);
        }
    }

    // A text must fit its column, and a value that a write checks by a rule of its own must pass the rule. A write
    // stores the value that the rule gives back.
    private static void requireFit(Map<String, String> params, String field, Object value, Integer length) {
        Map<String, String> named = new HashMap<>(params);
        named.put("field", field);
        if (value instanceof String text && length != null && !Checks.fits(text, length)) {
            throw FuhitoException.invalid("restore.bad_value", named);
        }
        Object stored;
        try {
            stored = rule(params.get("list"), field, value);
        } catch (FuhitoException | DateTimeException e) {
            throw FuhitoException.invalid("restore.bad_value", named).causedBy(e);
        }
        if (!Objects.equals(stored, value)) {
            throw FuhitoException.invalid("restore.bad_value", named);
        }
    }

    private static Object rule(String list, String field, Object value) {
        if (value == null) {
            return null;
        }
        if (DATES.contains(field)) {
            Checks.optionalDate(field, (String) value);
        } else if (TIMES.contains(field)) {
            return time((String) value);
        } else if (field.equals("version")) {
            return version((Long) value);
        }
        return switch (list + "." + field) {
            case "ref-kinds.code", "ref-items.code", "hierarchies.code", "org-unit-versions.code", "org-units.source",
                    "people.source", "employments.source", "employments.staffNumber", "assignments.source",
                    "qualifications.source", "external-ids.source", "change-orders.number", "change-orders.kind" ->
                    Checks.code(field, (String) value);
            case "hierarchies.name", "qualifications.number", "change-orders.title" ->
                    Checks.name(field, (String) value);
            case "attribute-definitions.name" -> AttributeDefinitionService.name((String) value);
            case "external-ids.externalId" -> ExternalIdService.value((String) value);
            case "employment-terms.workPercent", "assignments.allocationPercent" ->
                    Checks.optionalPercent(field, (BigDecimal) value);
            // A write strips the time settings.
            case "org-units.timeZone" -> {
                ANY_DAY.with((String) value, null);
                yield ((String) value).strip();
            }
            case "org-units.dayStart" -> {
                ANY_DAY.with(null, (String) value);
                yield ((String) value).strip();
            }
            default -> value;
        };
    }

    // A write gives 1 to a new resource and counts it up by one.
    private static Long version(Long value) {
        if (value < 1 || value > RealmExport.LARGEST_VERSION) {
            throw FuhitoException.invalid("invalid.out_of_range", Map.of("field", "version", "min", "1",
                    "max", Long.toString(RealmExport.LARGEST_VERSION)));
        }
        return value;
    }

    // An export gives a time as Instant writes it, and PostgreSQL keeps microseconds.
    private static String time(String value) {
        Instant time = Instant.parse(value);
        Checks.inRange(LocalDate.ofInstant(time, ZoneOffset.UTC));
        return time.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static void choice(String list, String field, String value) {
        switch (list + "." + field) {
            case "attribute-definitions.target" ->
                    Checks.optionalEnum(field, AttributeDefinition.Target.class, value, null);
            case "attribute-definitions.type" ->
                    Checks.optionalEnum(field, AttributeDefinition.Type.class, value, null);
            case "employment-terms.status" -> Checks.optionalEnum(field, EmploymentStatus.class, value, null);
            case "reporting-lines.kind" -> Checks.optionalEnum(field, ReportingLine.Kind.class, value, null);
            case "external-ids.resourceType" -> Checks.optionalEnum(field, ExternalId.Kind.class, value, null);
            default -> {
            }
        }
    }

    /**
     * Requires that every reference of an export names a row of the same export. A parent link names its hierarchy by
     * code, and may name the default hierarchy with no row of it.
     *
     * @param document the export
     * @throws FuhitoException when a reference names a row that the export does not have
     */
    static void requireReferences(RealmExport document) {
        new RestoreCheck(document).check(document);
    }

    private void check(RealmExport document) {
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            for (RealmExport.Version v : all(unit.versions())) {
                optional("org-units", "unitTypeId", v.unitTypeId(), refItems);
                optional("org-units", "workLocationId", v.workLocationId(), refItems);
                optional("org-units", "costCenterId", v.costCenterId(), refItems);
            }
            for (RealmExport.Parent p : all(unit.parents())) {
                required("org-units", "hierarchy", p.hierarchy(), hierarchyCodes);
                required("org-units", "parentId", p.parentId(), orgUnits);
            }
            all(unit.successors()).forEach(s -> required("org-units", "successorId", s.successorId(), orgUnits));
        }
        for (RealmExport.EmploymentRow e : all(document.employments())) {
            required("employments", "personId", e.personId(), people);
            if (e.externalEmployerId() == null) {
                required("employments", "employerId", e.employerId(), orgUnits);
            } else if (e.employerId() != null) {
                throw RestoreRules.broken("employments", e.id(), "externalEmployerId", "employment.employer_twice");
            } else {
                required("employments", "externalEmployerId", e.externalEmployerId(), refItems);
            }
            optional("employments", "endReasonId", e.endReasonId(), refItems);
            optional("employments", "continuesEmploymentId", e.continuesEmploymentId(), employments);
            for (RealmExport.Terms t : all(e.terms())) {
                optional("employments", "employmentTypeId", t.employmentTypeId(), refItems);
                optional("employments", "gradeId", t.gradeId(), refItems);
                optional("employments", "leaveReasonId", t.leaveReasonId(), refItems);
            }
        }
        for (RealmExport.AssignmentRows a : all(document.assignments())) {
            for (RealmExport.Period p : all(a.periods())) {
                required("assignments", "employmentId", p.employmentId(), employments);
                required("assignments", "orgUnitId", p.orgUnitId(), orgUnits);
                optional("assignments", "jobTitleId", p.jobTitleId(), refItems);
                optional("assignments", "occupationId", p.occupationId(), refItems);
                optional("assignments", "kindId", p.kindId(), refItems);
                optional("assignments", "workLocationId", p.workLocationId(), refItems);
                optional("assignments", "costCenterId", p.costCenterId(), refItems);
                optional("assignments", "continuesAssignmentId", p.continuesAssignmentId(), assignments);
            }
        }
        for (RealmExport.Line l : all(document.reportingLines())) {
            required("reporting-lines", "assignmentId", l.assignmentId(), assignments);
            required("reporting-lines", "managerPersonId", l.managerPersonId(), people);
        }
        for (RealmExport.Role r : all(document.unitRoles())) {
            required("unit-roles", "employmentId", r.employmentId(), employments);
            required("unit-roles", "orgUnitId", r.orgUnitId(), orgUnits);
            required("unit-roles", "roleId", r.roleId(), refItems);
        }
        for (RealmExport.QualificationRow q : all(document.qualifications())) {
            required("qualifications", "personId", q.personId(), people);
            required("qualifications", "kindId", q.kindId(), refItems);
        }
        for (RealmExport.ExternalIdRow x : all(document.externalIds())) {
            Set<String> named = switch (Optional.ofNullable(x.resourceType()).orElse("")) {
                case "ORG_UNIT" -> orgUnits;
                case "PERSON" -> people;
                case "EMPLOYMENT" -> employments;
                case "ASSIGNMENT" -> assignments;
                case "QUALIFICATION" -> qualifications;
                case "REPORTING_LINE" -> lines;
                case "UNIT_ROLE" -> roles;
                case "CHANGE_ORDER" -> orders;
                case "REF_ITEM" -> refItems;
                case "REF_KIND" -> refKinds;
                case "HIERARCHY" -> hierarchies;
                case "ATTRIBUTE_DEFINITION" -> definitions;
                default -> Set.of();
            };
            required("external-ids", "resourceId", x.resourceId(), named);
        }
    }

    private static void optional(String list, String field, String value, Set<String> known) {
        if (value != null) {
            required(list, field, value, known);
        }
    }

    private static void required(String list, String field, String value, Set<String> known) {
        if (value == null || !known.contains(value)) {
            throw FuhitoException.invalid("restore.reference_unknown",
                    Map.of("list", list, "field", field, "value", String.valueOf(value)));
        }
    }

    private static <T> Set<String> ids(List<T> rows, Function<T, String> id) {
        Set<String> ids = new HashSet<>();
        all(rows).forEach(row -> ids.add(id.apply(row)));
        return ids;
    }

    private static <T> List<T> all(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}

package net.exoego.keycloak.fuhito.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Checks that the rows of an export keep the unique keys and the rules of the periods of the database. A restore
 * checks them before it writes, so that a row that breaks one fails with an error that names it. An export never
 * has such rows, because every write keeps the rules.
 */
final class RestoreKeys {

    /**
     * The IDs of the rows of a list, in the order of the export.
     *
     * @param list the list of an export, or a part of one such as {@code org-unit-versions}
     * @param field the field of a row that has its ID
     * @param ids the IDs
     */
    record Ids(String list, String field, List<String> ids) {
    }

    private record Child<T>(String parentId, T row) {
    }

    private record Dated(String id, Object group, ValidPeriod period) {
    }

    private RestoreKeys() {
    }

    /**
     * Returns the IDs of every list that a restore writes. An assignment has its own ID and one for each row.
     *
     * @param document the export
     * @return the IDs of each list
     */
    static List<Ids> ids(RealmExport document) {
        List<RealmExport.Unit> units = all(document.orgUnits());
        List<RealmExport.AssignmentRows> assignments = all(document.assignments());
        return List.of(
                ids("ref-kinds", "id", document.refKinds(), RealmExport.Kind::id),
                ids("ref-items", "id", document.refItems(), RealmExport.Item::id),
                ids("ref-item-names", "id", flat(document.refItems(), RealmExport.Item::versions),
                        RealmExport.ItemNames::id),
                ids("attribute-definitions", "id", document.attributeDefinitions(), RealmExport.Definition::id),
                ids("hierarchies", "id", document.hierarchies(), RealmExport.HierarchyRow::id),
                ids("org-units", "id", units, RealmExport.Unit::id),
                ids("org-unit-versions", "id", flat(units, RealmExport.Unit::versions), RealmExport.Version::id),
                ids("org-unit-parents", "id", flat(units, RealmExport.Unit::parents), RealmExport.Parent::id),
                ids("org-unit-successions", "id", flat(units, RealmExport.Unit::successors),
                        RealmExport.Succession::id),
                ids("people", "id", document.people(), RealmExport.PersonRow::id),
                ids("employments", "id", document.employments(), RealmExport.EmploymentRow::id),
                ids("employment-terms", "id", flat(document.employments(), RealmExport.EmploymentRow::terms),
                        RealmExport.Terms::id),
                ids("assignments", "assignmentId", assignments, RealmExport.AssignmentRows::assignmentId),
                ids("assignments", "rowId", flat(assignments, RealmExport.AssignmentRows::periods),
                        RealmExport.Period::rowId),
                ids("reporting-lines", "id", document.reportingLines(), RealmExport.Line::id),
                ids("unit-roles", "id", document.unitRoles(), RealmExport.Role::id),
                ids("qualifications", "id", document.qualifications(), RealmExport.QualificationRow::id),
                ids("external-ids", "id", document.externalIds(), RealmExport.ExternalIdRow::id),
                ids("change-orders", "id", document.changeOrders(), RealmExport.Order::id));
    }

    /**
     * Requires that no two rows of an export have the same ID or the same unique key, and that no two rows that the
     * database keeps apart are valid on the same date.
     *
     * @param document the export, with every value that its tables need
     * @throws FuhitoException when two rows have the same ID or key, or overlap. The error names the list, the ID of
     *         the row, the field, and the other row.
     */
    static void require(RealmExport document) {
        for (Ids ids : ids(document)) {
            unique(ids.list(), ids.field(), ids.ids(), Function.identity(), Function.identity());
        }
        unique("erasures", "personId", all(document.erasures()), RealmExport.Erasure::personId,
                RealmExport.Erasure::personId);
        unique("ref-kinds", "code", all(document.refKinds()), RealmExport.Kind::id, RealmExport.Kind::code);
        unique("ref-items", "code", all(document.refItems()), RealmExport.Item::id, i -> List.of(i.kind(), i.code()));
        unique("attribute-definitions", "name", all(document.attributeDefinitions()), RealmExport.Definition::id,
                d -> List.of(d.target(), d.name()));
        unique("hierarchies", "code", all(document.hierarchies()), RealmExport.HierarchyRow::id,
                RealmExport.HierarchyRow::code);
        unique("org-unit-successions", "successorId", children(document.orgUnits(), RealmExport.Unit::id,
                RealmExport.Unit::successors), c -> c.row().id(), c -> List.of(c.parentId(), c.row().successorId()));
        unique("change-orders", "number", all(document.changeOrders()), RealmExport.Order::id,
                RealmExport.Order::number);
        List<RealmExport.ExternalIdRow> externalIds = all(document.externalIds());
        unique("external-ids", "externalId", externalIds, RealmExport.ExternalIdRow::id,
                x -> List.of(x.resourceType(), x.source(), x.externalId()));
        unique("external-ids", "source", externalIds, RealmExport.ExternalIdRow::id,
                x -> List.of(x.resourceType(), x.resourceId(), x.source()));
        requireApart(document);
    }

    private static void requireApart(RealmExport document) {
        apart("ref-item-names", "validFrom", children(document.refItems(), RealmExport.Item::id,
                RealmExport.Item::versions).stream()
                .map(c -> dated(c.row().id(), c.parentId(), c.row().validFrom(), c.row().validTo())));
        List<Child<RealmExport.Version>> versions = children(document.orgUnits(), RealmExport.Unit::id,
                RealmExport.Unit::versions);
        apart("org-unit-versions", "validFrom", versions.stream()
                .map(c -> dated(c.row().id(), c.parentId(), c.row().validFrom(), c.row().validTo())));
        apart("org-unit-versions", "code", versions.stream()
                .map(c -> dated(c.row().id(), c.row().code(), c.row().validFrom(), c.row().validTo())));
        apart("org-unit-parents", "hierarchy", children(document.orgUnits(), RealmExport.Unit::id,
                RealmExport.Unit::parents).stream().map(c -> dated(c.row().id(),
                List.of(c.parentId(), c.row().hierarchy()), c.row().validFrom(), c.row().validTo())));
        apart("employment-terms", "validFrom", children(document.employments(), RealmExport.EmploymentRow::id,
                RealmExport.EmploymentRow::terms).stream()
                .map(c -> dated(c.row().id(), c.parentId(), c.row().validFrom(), c.row().validTo())));
        List<Child<RealmExport.Period>> rows = children(document.assignments(),
                RealmExport.AssignmentRows::assignmentId, RealmExport.AssignmentRows::periods);
        apart("assignments", "validFrom", rows.stream()
                .map(c -> dated(c.row().rowId(), c.parentId(), c.row().validFrom(), c.row().validTo())));
        apart("assignments", "primary", rows.stream().filter(c -> c.row().primary())
                .map(c -> dated(c.row().rowId(), c.row().employmentId(), c.row().validFrom(), c.row().validTo())));
        apart("unit-roles", "roleId", all(document.unitRoles()).stream().map(r -> dated(r.id(),
                List.of(r.employmentId(), r.orgUnitId(), r.roleId()), r.validFrom(), r.validTo())));
    }

    /**
     * Returns the error of a row that has the same key as another row.
     *
     * @param list the list
     * @param id the ID of the row
     * @param field the field of the key
     * @param other the ID of the other row
     * @return the error
     */
    static FuhitoException duplicate(String list, String id, String field, String other) {
        return FuhitoException.invalid("restore.duplicate", Map.of("list", list, "id", id, "field", field,
                "other", other));
    }

    // A null is no key, as in the database.
    private static <T> void unique(String list, String field, List<T> rows, Function<T, String> id,
            Function<T, ?> key) {
        Map<Object, String> first = new HashMap<>();
        for (T row : rows) {
            Object value = key.apply(row);
            String other = value == null ? null : first.putIfAbsent(value, id.apply(row));
            if (other != null) {
                throw duplicate(list, id.apply(row), field, other);
            }
        }
    }

    // Two rows of a group must not share a date. Sorted by their starts, each row must end before the next starts.
    private static void apart(String list, String field, Stream<Dated> rows) {
        Map<Object, List<Dated>> groups = new LinkedHashMap<>();
        rows.filter(row -> row.group() != null && row.period() != null)
                .forEach(row -> groups.computeIfAbsent(row.group(), group -> new ArrayList<>()).add(row));
        for (List<Dated> group : groups.values()) {
            group.sort(Comparator.comparing((Dated row) -> row.period().from()));
            for (int i = 1; i < group.size(); i++) {
                if (group.get(i - 1).period().overlaps(group.get(i).period())) {
                    throw FuhitoException.invalid("restore.overlap", Map.of("list", list, "id", group.get(i).id(),
                            "field", field, "other", group.get(i - 1).id()));
                }
            }
        }
    }

    // Names with no start hold from the start. A period that cannot be read fails in the write, as before.
    private static Dated dated(String id, Object group, String from, String to) {
        try {
            return new Dated(id, group, new ValidPeriod(from == null ? LocalDate.MIN : LocalDate.parse(from),
                    to == null ? null : LocalDate.parse(to)));
        } catch (DateTimeException | IllegalArgumentException e) {
            return new Dated(id, group, null);
        }
    }

    private static <T> Ids ids(String list, String field, List<T> rows, Function<T, String> id) {
        return new Ids(list, field, all(rows).stream().map(id).toList());
    }

    private static <P, T> List<T> flat(List<P> parents, Function<P, List<T>> rows) {
        return all(parents).stream().flatMap(p -> all(rows.apply(p)).stream()).toList();
    }

    private static <P, T> List<Child<T>> children(List<P> parents, Function<P, String> id,
            Function<P, List<T>> rows) {
        return all(parents).stream()
                .flatMap(p -> all(rows.apply(p)).stream().map(row -> new Child<>(id.apply(p), row)))
                .toList();
    }

    private static <T> List<T> all(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}

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

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The org units of one document. A parent is applied before its children, wherever it is in the list. The list reads
 * what its rows name with one read for each kind, and stores its new units together. A row that changes a stored
 * unit first stores the new units of the rows before it, so that its checks see them. The writes of the list come
 * into the report in the order in which its rows are applied, once the new units are stored.
 */
final class OrgUnitImport {

    private static final String ORG_UNITS = "orgUnits";

    private final OrgUnitService orgUnitService;
    private final AttributeRules rules;
    private final OrgUnitService.NewOrgUnits units;
    private final ListIds ids;
    private final ImportService.Organizations organizations;
    private final String effectiveDate;
    private final String defaultSource;
    private final List<OrgUnitRow> rows;
    private final ImportReport report;
    private final Map<String, Integer> rowByKey = new HashMap<>();
    private final Map<Integer, String> unitIdByRow = new HashMap<>();
    private final Set<Integer> done = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private final Set<Integer> running = new HashSet<>();
    private final List<Ending> endings = new ArrayList<>();
    private final List<Supplier<Write>> writes = new ArrayList<>();
    private final Runnable rowDone;

    // An end with successors waits for every row, because a successor can come later in the document.
    private record Ending(int index, String unitId, String source, String validTo, OrgUnitRow row) {
    }

    OrgUnitImport(OrgUnitService orgUnitService, ExternalIdService externalIds,
            ImportService.Organizations organizations, String effectiveDate, String defaultSource,
            List<OrgUnitRow> rows, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.orgUnitService = orgUnitService.remembering();
        this.rules = this.orgUnitService.attributeRules();
        this.ids = new ListIds(externalIds);
        this.units = this.orgUnitService.newOrgUnits(
                (source, value) -> ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, value));
        this.organizations = organizations;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.rows = rows;
        this.report = report;
    }

    void run() {
        report.startList(ORG_UNITS);
        for (int i = 0; i < rows.size(); i++) {
            OrgUnitRow row = rows.get(i);
            if (row == null || (row.externalId() == null && row.id() == null)) {
                fail(i, FuhitoException.invalid("invalid.required", Map.of("field", "externalId")));
            } else if (rowByKey.putIfAbsent(key(row), i) != null) {
                fail(i, FuhitoException.invalid("import.duplicate_row", Map.of("externalId", name(row))));
            } else {
                try {
                    EmptyValues.withoutEmptyValues(row);
                } catch (FuhitoException e) {
                    fail(i, e);
                }
            }
            if (EmptyValues.passes(row)) {
                ids.want(ExternalId.Kind.ORG_UNIT, sourceOf(row), row.externalId())
                        .want(ExternalId.Kind.ORG_UNIT, sourceOf(row), row.parentExternalId());
                successorExternalIds(row).forEach(successor -> ids.want(ExternalId.Kind.ORG_UNIT, sourceOf(row),
                        successor));
            }
        }
        ids.read();
        read();
        for (int i = 0; i < rows.size(); i++) {
            apply(i);
            rowDone.run();
        }
        units.insert();
        writes.forEach(write -> report.write(write.get()));
        rowDone.run();
        for (Ending ending : endings) {
            succeed(ending);
            rowDone.run();
        }
    }

    // The stored units that the rows name by ID and by an ID of another system, and the codes of the rows.
    private void read() {
        Set<String> named = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (OrgUnitRow row : rows) {
            if (!EmptyValues.passes(row)) {
                continue;
            }
            List<String> externalIds = new ArrayList<>(successorExternalIds(row));
            externalIds.add(row.externalId());
            externalIds.add(row.parentExternalId());
            externalIds.stream().filter(Objects::nonNull).forEach(externalId -> ids.resourceOf(
                    ExternalId.Kind.ORG_UNIT, sourceOf(row), externalId).ifPresent(named::add));
            List<String> given = new ArrayList<>(Optional.ofNullable(row.successorIds()).orElse(List.of()));
            given.add(row.id());
            given.add(row.parentId());
            given.stream().filter(Objects::nonNull).forEach(named::add);
            if (row.code() != null) {
                codes.add(row.code());
            }
        }
        units.read(named, codes);
    }

    private static List<String> successorExternalIds(OrgUnitRow row) {
        return Optional.ofNullable(row.successorExternalIds()).orElse(List.of());
    }

    private static boolean hasSuccessors(OrgUnitRow row) {
        return (row.successorExternalIds() != null && !row.successorExternalIds().isEmpty())
                || (row.successorIds() != null && !row.successorIds().isEmpty());
    }

    // A unit that ended on the same date with the same successors stays as it is, so a document can come again.
    private void succeed(Ending ending) {
        if (failed.contains(ending.index())) {
            return;
        }
        try {
            List<String> successors = successorIds(ending.row(), ending.source());
            String end = lastDay(ending.unitId());
            if (end == null) {
                report.write(new Write.OfOrgUnit(false, "/end", units.write(ending.unitId(),
                        () -> orgUnitService.succeed(ending.unitId(), ending.validTo(), successors))));
                report.write(new Write.OfSuccession(ending.unitId(), orgUnitService.successions(ending.unitId())));
                return;
            }
            Set<String> stored = new HashSet<>();
            orgUnitService.successions(ending.unitId()).successors().stream()
                    .filter(link -> link.effectiveDate().toString().equals(end))
                    .forEach(link -> stored.add(link.successorId()));
            if (!end.equals(ending.validTo()) || !stored.equals(new HashSet<>(successors))) {
                throw FuhitoException.conflict("import.successors_differ", Map.of("id", ending.unitId(),
                        "validTo", end));
            }
        } catch (FuhitoException e) {
            fail(ending.index(), e);
        }
    }

    private List<String> successorIds(OrgUnitRow row, String source) {
        List<String> found = new ArrayList<>();
        for (String externalId : successorExternalIds(row)) {
            found.add(ids.resourceOf(ExternalId.Kind.ORG_UNIT, source,
                            Checks.required("successorExternalIds", externalId))
                    .orElseThrow(() -> FuhitoException.invalid("import.successor_unknown",
                            Map.of("successorExternalId", externalId))));
        }
        for (String id : Optional.ofNullable(row.successorIds()).orElse(List.of())) {
            if (units.unit(Checks.required("successorIds", id)).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", id));
            }
            found.add(id);
        }
        return found;
    }

    private String sourceOf(OrgUnitRow row) {
        return row.source() == null ? defaultSource : row.source();
    }

    // A row with no external ID names a stored unit by its ID.
    private String key(OrgUnitRow row) {
        return row.externalId() == null ? "\\u0000id\\u0000" + row.id() : sourceOf(row) + "\\u0000" + row.externalId();
    }

    private static String name(OrgUnitRow row) {
        return row.externalId() == null ? row.id() : row.externalId();
    }

    private void fail(int index, FuhitoException error) {
        failed.add(index);
        done.add(index);
        report.error(ORG_UNITS, index, error);
    }

    private void apply(int index) {
        if (done.contains(index)) {
            return;
        }
        if (!running.add(index)) {
            throw FuhitoException.invalid("import.parent_cycle",
                    Map.of("externalId", name(rows.get(index))));
        }
        try {
            OrgUnitRow row = rows.get(index);
            // The parent of a row is in the default hierarchy. The list orgUnitParents gives the others.
            Checks.fixed("hierarchy", Hierarchy.DEFAULT_CODE, row.hierarchy());
            String source = Checks.code("source", sourceOf(row));
            String parentId = parentId(row, source);
            report.count(ORG_UNITS, applyRow(index, row, source, parentId));
        } catch (FuhitoException e) {
            fail(index, e);
        } finally {
            running.remove(index);
            done.add(index);
        }
    }

    // Applies the parent row first when the parent is in the same document.
    private String parentId(OrgUnitRow row, String source) {
        if (row.parentExternalId() != null && row.parentId() != null) {
            throw FuhitoException.invalid("import.parent_twice",
                    Map.of("parentExternalId", row.parentExternalId(), "parentId", row.parentId()));
        }
        if (row.parentExternalId() == null) {
            if (row.parentId() != null && units.unit(row.parentId()).isEmpty()) {
                throw FuhitoException.invalid("import.parent_id_unknown", Map.of("parentId", row.parentId()));
            }
            return row.parentId();
        }
        Integer parentRow = rowByKey.get(source + "\\u0000" + row.parentExternalId());
        if (parentRow != null) {
            apply(parentRow);
            if (failed.contains(parentRow)) {
                throw FuhitoException.invalid("import.parent_failed",
                        Map.of("parentExternalId", row.parentExternalId()));
            }
            return unitIdByRow.get(parentRow);
        }
        return ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.parentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.parent_unknown",
                        Map.of("parentExternalId", row.parentExternalId())));
    }

    private Outcome applyRow(int index, OrgUnitRow row, String source, String parentId) {
        boolean succeeds = hasSuccessors(row);
        Optional<String> stored = stored(row, source);
        if (stored.isEmpty()) {
            if (succeeds && row.validTo() == null) {
                throw FuhitoException.invalid("invalid.required", Map.of("field", "validTo"));
            }
            NewOrgUnit request = new NewOrgUnit(row.code(), row.primaryLanguage(),
                    Merged.names(row, row.names(), null, Merged.UNIT_NAMES), row.unitType(), row.workLocation(),
                    row.costCenter(), parentId, row.validFrom() == null ? effectiveDate : row.validFrom(),
                    row.displayOrder(), Merged.attributes(row, row.attributes(), null, rules, Target.ORG_UNIT),
                    row.externalId(), source);
            String id = ids.create(ExternalId.Kind.ORG_UNIT, source, row.externalId(),
                    () -> units.create(request), state -> state.unit().id()).unit().id();
            unitIdByRow.put(index, id);
            writes.add(() -> new Write.OfOrgUnit(true, "", units.state(id)));
            link(id, row);
            time(id, row);
            if (succeeds) {
                endings.add(new Ending(index, id, source, row.validTo(), row));
            } else if (row.validTo() != null) {
                written("/end", id, () -> orgUnitService.end(id, row.validTo()));
            }
            return Outcome.CREATED;
        }
        String id = stored.get();
        unitIdByRow.put(index, id);
        List<OrgUnitVersion> versions = units.versions(id);
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        LocalDate end = versions.getLast().period().to();
        LocalDate first = versions.getFirst().period().from();
        ImportMatch.start(row, id, first, row.validFrom());
        String validTo = row.or("validTo", row.validTo(), Objects.toString(end, null));
        if (succeeds && validTo == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "validTo"));
        }
        if (!succeeds && end != null && clearsSuccessors(row)
                && !orgUnitService.successions(id).successors().isEmpty()) {
            throw FuhitoException.conflict("import.successors_differ", Map.of("id", id, "validTo", lastDay(id)));
        }
        // A row that leaves out the parent keeps the stored one.
        boolean keepsParent = !row.gives("parentExternalId", row.parentExternalId())
                && !row.gives("parentId", row.parentId());
        boolean changed = false;
        // A unit that ended by the effective date keeps its details. One that starts later can only come again as
        // it is, for example in the document that made it.
        if (first.isAfter(date)) {
            OrgUnitState onFirst = units.stateOn(id, first);
            String parent = keepsParent ? onFirst.parentId() : parentId;
            if (!quietlySame(row, onFirst) || !Objects.equals(parent, onFirst.parentId())) {
                throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", effectiveDate));
            }
        } else if (end == null || end.isAfter(date)) {
            OrgUnitState before = units.stateOn(id, date);
            String parent = keepsParent ? before.parentId() : parentId;
            boolean moves = !Objects.equals(parent, before.parentId());
            // A unit that becomes a rollup unit leaves its parent first. A rollup unit has no parent.
            if (moves && parent == null) {
                changed("/parent", id, () -> orgUnitService.move(id, null, effectiveDate));
            }
            OrgUnitChange details = details(row, before);
            if (!sameDetails(details, before)) {
                changed("", id, () -> orgUnitService.changeDetails(id, details));
                changed = true;
            }
            if (moves && parent != null) {
                changed("/parent", id, () -> orgUnitService.move(id, parent, effectiveDate));
            }
            changed |= moves;
        }
        changed |= link(id, row);
        changed |= time(id, row);
        if (succeeds) {
            endings.add(new Ending(index, id, source, validTo, row));
            changed |= lastDay(id) == null;
        } else if (validTo == null && lastDay(id) != null) {
            written("/end", id, () -> orgUnitService.removeEnd(id));
            changed = true;
        } else if (validTo != null && !validTo.equals(lastDay(id))) {
            written("/end", id, () -> orgUnitService.end(id, validTo));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private static boolean clearsSuccessors(OrgUnitRow row) {
        return row.gives("successorExternalIds", row.successorExternalIds())
                || row.gives("successorIds", row.successorIds());
    }

    // A row with an ID changes that unit. An external ID beside it must be one of that unit.
    private Optional<String> stored(OrgUnitRow row, String source) {
        if (row.id() == null) {
            return ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.externalId());
        }
        OrgUnit unit = units.unit(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        ImportMatch.source(row.id(), unit.source(), row.source(), row.externalId(), row.parentExternalId(),
                row.successorExternalIds());
        if (row.externalId() != null && !ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.externalId())
                .equals(Optional.of(row.id()))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", row.externalId()));
        }
        return Optional.of(row.id());
    }

    private boolean time(String id, OrgUnitRow row) {
        if (!row.gives("timeZone", row.timeZone()) && !row.gives("dayStart", row.dayStart())) {
            return false;
        }
        OrgUnit unit = units.unit(id).orElseThrow();
        String zone = row.or("timeZone", row.timeZone(), unit.timeZone());
        String start = row.or("dayStart", row.dayStart(), unit.dayStart());
        if (Objects.equals(zone, unit.timeZone()) && Objects.equals(start, unit.dayStart())) {
            return false;
        }
        OrgUnit changed = units.write(id, () -> orgUnitService.setTime(id, zone, start));
        Write write = new Write.OfTimeSettings(id, changed.timeZone(), changed.dayStart(), changed.version());
        writes.add(() -> write);
        return true;
    }

    // A link decides who is a member of the organization, so the caller must manage the old and the new one.
    private boolean link(String id, OrgUnitRow row) {
        String alias = row.organizationAlias();
        if (!row.gives("organizationAlias", alias)) {
            return false;
        }
        String wanted = alias == null ? null : organizations.directory().idOfAlias(alias).orElseThrow(
                () -> FuhitoException.invalid("import.organization_unknown", Map.of("alias", alias)));
        String current = units.unit(id).orElseThrow().keycloakOrganizationId();
        if (Objects.equals(current, wanted)) {
            return false;
        }
        if (current != null) {
            organizations.manage().accept(current);
        }
        if (wanted != null) {
            organizations.manage().accept(wanted);
        }
        OrgUnit linked = units.write(id, () -> wanted == null ? organizations.links().unlink(id)
                : organizations.links().link(id, wanted));
        Write write = new Write.OfOrganizationLink(id, wanted, linked.version());
        writes.add(() -> write);
        return true;
    }

    private String lastDay(String id) {
        return Objects.toString(units.versions(id).getLast().period().to(), null);
    }

    private OrgUnitChange details(OrgUnitRow row, OrgUnitState stored) {
        OrgUnitVersion version = stored.version();
        return new OrgUnitChange(effectiveDate, row.or("code", row.code(), version.code()),
                row.or("primaryLanguage", row.primaryLanguage(), version.names().primaryLanguage()),
                Merged.names(row, row.names(), version.names().byLanguage(), Merged.UNIT_NAMES),
                row.or("unitType", row.unitType(), stored.unitType()),
                row.or("workLocation", row.workLocation(), stored.places().workLocation()),
                row.or("costCenter", row.costCenter(), stored.places().costCenter()),
                row.or("displayOrder", row.displayOrder(), version.displayOrder()),
                Merged.attributes(row, row.attributes(), version.attributes(), rules, Target.ORG_UNIT));
    }

    // A bad value is not the same as what is stored.
    private boolean quietlySame(OrgUnitRow row, OrgUnitState stored) {
        try {
            return sameDetails(details(row, stored), stored);
        } catch (FuhitoException e) {
            return false;
        }
    }

    private static boolean sameDetails(OrgUnitChange wanted, OrgUnitState before) {
        var version = before.version();
        return Objects.equals(Checks.optionalCode("code", wanted.code()), version.code())
                && Localized.check(wanted.primaryLanguage(), wanted.names(), (field, name) -> name.checked(field))
                        .equals(version.names())
                && Objects.equals(wanted.unitType(), before.unitType())
                && Objects.equals(wanted.workLocation(), before.places().workLocation())
                && Objects.equals(wanted.costCenter(), before.places().costCenter())
                && (wanted.displayOrder() == null ? 0 : wanted.displayOrder()) == version.displayOrder()
                && (wanted.attributes() == null ? Map.of() : wanted.attributes()).equals(version.attributes());
    }

    private void written(String suffix, String id, Supplier<OrgUnitState> write) {
        OrgUnitState after = units.write(id, write);
        writes.add(() -> new Write.OfOrgUnit(false, suffix, after));
    }

    private void changed(String suffix, String id, Supplier<OrgUnitService.Written> write) {
        OrgUnitService.Written after = units.write(id, write);
        writes.add(() -> new Write.OfOrgUnit(suffix, after));
    }
}

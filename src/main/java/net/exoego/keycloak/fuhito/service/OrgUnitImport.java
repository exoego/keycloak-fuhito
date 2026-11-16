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

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The org units of one document. A parent is applied before its children, wherever it is in the list.
 */
final class OrgUnitImport {

    private static final String ORG_UNITS = "orgUnits";

    private final OrgUnitService orgUnitService;
    private final OrgUnitStore orgUnits;
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

    // An end with successors waits for every row, because a successor can come later in the document.
    private record Ending(int index, String unitId, String source, OrgUnitRow row) {
    }

    OrgUnitImport(OrgUnitService orgUnitService, OrgUnitStore orgUnits, String effectiveDate, String defaultSource,
            List<OrgUnitRow> rows, ImportReport report) {
        this.orgUnitService = orgUnitService;
        this.orgUnits = orgUnits;
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
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            apply(i);
        }
        endings.forEach(this::succeed);
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
                written(false, "/end", orgUnitService.succeed(ending.unitId(), ending.row().validTo(), successors));
                report.write(new Write.OfSuccession(ending.unitId(), orgUnitService.successions(ending.unitId())));
                return;
            }
            Set<String> stored = new HashSet<>();
            orgUnitService.successions(ending.unitId()).successors().stream()
                    .filter(link -> link.effectiveDate().toString().equals(end))
                    .forEach(link -> stored.add(link.successorId()));
            if (!end.equals(ending.row().validTo()) || !stored.equals(new HashSet<>(successors))) {
                throw FuhitoException.conflict("import.successors_differ", Map.of("id", ending.unitId(),
                        "validTo", end));
            }
        } catch (FuhitoException e) {
            fail(ending.index(), e);
        }
    }

    private List<String> successorIds(OrgUnitRow row, String source) {
        List<String> ids = new ArrayList<>();
        for (String externalId : Optional.ofNullable(row.successorExternalIds()).orElse(List.of())) {
            ids.add(orgUnits.findUnitByExternalId(source, Checks.required("successorExternalIds", externalId))
                    .map(OrgUnit::id)
                    .orElseThrow(() -> FuhitoException.invalid("import.successor_unknown",
                            Map.of("successorExternalId", externalId))));
        }
        for (String id : Optional.ofNullable(row.successorIds()).orElse(List.of())) {
            if (orgUnits.findUnit(Checks.required("successorIds", id)).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", id));
            }
            ids.add(id);
        }
        return ids;
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
            if (row.parentId() != null && orgUnits.findUnit(row.parentId()).isEmpty()) {
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
        return orgUnits.findUnitByExternalId(source, row.parentExternalId())
                .map(OrgUnit::id)
                .orElseThrow(() -> FuhitoException.invalid("import.parent_unknown",
                        Map.of("parentExternalId", row.parentExternalId())));
    }

    private Outcome applyRow(int index, OrgUnitRow row, String source, String parentId) {
        boolean succeeds = hasSuccessors(row);
        if (succeeds && row.validTo() == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "validTo"));
        }
        Optional<OrgUnit> stored = stored(row, source);
        if (stored.isEmpty()) {
            OrgUnitState created = orgUnitService.create(new NewOrgUnit(row.code(), row.name(), row.names(),
                    row.shortName(), row.shortNames(), row.phoneticName(), row.unitType(), parentId,
                    row.validFrom() == null ? effectiveDate : row.validFrom(), row.displayOrder(),
                    row.attributes(), row.externalId(), source));
            String id = created.unit().id();
            unitIdByRow.put(index, id);
            written(true, "", created);
            if (succeeds) {
                endings.add(new Ending(index, id, source, row));
            } else if (row.validTo() != null) {
                written(false, "/end", orgUnitService.end(id, row.validTo()));
            }
            return Outcome.CREATED;
        }
        String id = stored.get().id();
        unitIdByRow.put(index, id);
        List<OrgUnitVersion> versions = orgUnits.versions(id);
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        LocalDate end = versions.getLast().period().to();
        LocalDate first = versions.getFirst().period().from();
        boolean changed = false;
        // A unit that ended by the effective date keeps its details. One that starts later can only come again as
        // it is, for example in the document that made it.
        if (first.isAfter(date)) {
            OrgUnitState onFirst = orgUnitService.get(id, first.toString());
            if (!quietlySame(row, onFirst) || !Objects.equals(parentId, onFirst.parentId())) {
                throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", effectiveDate));
            }
        } else if (end == null || end.isAfter(date)) {
            OrgUnitState before = orgUnitService.get(id, effectiveDate);
            if (!sameDetails(row, before)) {
                written(false, "", orgUnitService.changeDetails(id, new OrgUnitChange(effectiveDate, row.code(),
                        row.name(), row.names(), row.shortName(), row.shortNames(), row.phoneticName(),
                        row.unitType(), row.displayOrder(), row.attributes())));
                changed = true;
            }
            if (!Objects.equals(parentId, before.parentId())) {
                written(false, "/parent", orgUnitService.move(id, parentId, effectiveDate));
                changed = true;
            }
        }
        if (succeeds) {
            endings.add(new Ending(index, id, source, row));
            changed |= lastDay(id) == null;
        } else if (row.validTo() != null && !row.validTo().equals(lastDay(id))) {
            written(false, "/end", orgUnitService.end(id, row.validTo()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    // A row with an ID changes that unit. An external ID beside it must be the one of that unit.
    private Optional<OrgUnit> stored(OrgUnitRow row, String source) {
        if (row.id() == null) {
            return orgUnits.findUnitByExternalId(source, row.externalId());
        }
        OrgUnit unit = orgUnits.findUnit(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        if (row.externalId() != null
                && !(row.externalId().equals(unit.externalId()) && source.equals(unit.source()))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", row.externalId()));
        }
        return Optional.of(unit);
    }

    private String lastDay(String id) {
        return Objects.toString(orgUnits.versions(id).getLast().period().to(), null);
    }

    // A bad value is not the same as what is stored.
    private boolean quietlySame(OrgUnitRow row, OrgUnitState stored) {
        try {
            return sameDetails(row, stored);
        } catch (FuhitoException e) {
            return false;
        }
    }

    private boolean sameDetails(OrgUnitRow row, OrgUnitState before) {
        var version = before.version();
        return Objects.equals(Checks.optionalCode("code", row.code()), version.code())
                && Checks.name("name", row.name()).equals(version.name())
                && Checks.localizedNames("names", row.names()).equals(version.names())
                && Objects.equals(Checks.optionalName("shortName", row.shortName()), version.shortName())
                && Checks.localizedNames("shortNames", row.shortNames()).equals(version.shortNames())
                && Objects.equals(Checks.optionalName("phoneticName", row.phoneticName()),
                        version.phoneticName())
                && Objects.equals(row.unitType(), before.unitType())
                && (row.displayOrder() == null ? 0 : row.displayOrder()) == version.displayOrder()
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(version.attributes());
    }

    private void written(boolean created, String suffix, OrgUnitState state) {
        report.write(new Write.OfOrgUnit(created, suffix, state));
    }
}

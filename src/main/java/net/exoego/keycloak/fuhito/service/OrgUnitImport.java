package net.exoego.keycloak.fuhito.service;

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
            if (row == null || row.externalId() == null) {
                fail(i, FuhitoException.invalid("invalid.required", Map.of("field", "externalId")));
            } else if (rowByKey.putIfAbsent(key(row), i) != null) {
                fail(i, FuhitoException.invalid("import.duplicate_row", Map.of("externalId", row.externalId())));
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            apply(i);
        }
    }

    private String sourceOf(OrgUnitRow row) {
        return row.source() == null ? defaultSource : row.source();
    }

    private String key(OrgUnitRow row) {
        return sourceOf(row) + "\\u0000" + row.externalId();
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
                    Map.of("externalId", rows.get(index).externalId()));
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
        if (row.parentExternalId() == null) {
            return null;
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
        Optional<OrgUnit> stored = orgUnits.findUnitByExternalId(source, row.externalId());
        if (stored.isEmpty()) {
            OrgUnitState created = orgUnitService.create(new NewOrgUnit(row.code(), row.name(), row.names(),
                    row.shortName(), row.shortNames(), row.phoneticName(), row.unitType(), parentId,
                    row.validFrom() == null ? effectiveDate : row.validFrom(), row.displayOrder(),
                    row.attributes(), row.externalId(), source));
            String id = created.unit().id();
            unitIdByRow.put(index, id);
            written(true, "", created);
            if (row.validTo() != null) {
                written(false, "/end", orgUnitService.end(id, row.validTo()));
            }
            return Outcome.CREATED;
        }
        String id = stored.get().id();
        unitIdByRow.put(index, id);
        OrgUnitState before = orgUnitService.get(id, effectiveDate);
        boolean changed = false;
        if (!sameDetails(row, before)) {
            written(false, "", orgUnitService.changeDetails(id, new OrgUnitChange(effectiveDate, row.name(),
                    row.names(), row.shortName(), row.shortNames(), row.phoneticName(), row.unitType(),
                    row.displayOrder(), row.attributes())));
            changed = true;
        }
        if (!Objects.equals(parentId, before.parentId())) {
            written(false, "/parent", orgUnitService.move(id, parentId, effectiveDate));
            changed = true;
        }
        if (row.validTo() != null && !row.validTo().equals(lastDay(id))) {
            written(false, "/end", orgUnitService.end(id, row.validTo()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private String lastDay(String id) {
        return Objects.toString(orgUnits.versions(id).getLast().period().to(), null);
    }

    private boolean sameDetails(OrgUnitRow row, OrgUnitState before) {
        var version = before.version();
        return Checks.name("name", row.name()).equals(version.name())
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

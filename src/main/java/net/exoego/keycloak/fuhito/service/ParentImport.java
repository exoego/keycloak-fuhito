package net.exoego.keycloak.fuhito.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitParentRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The parents of org units in hierarchies of one document. A row gives the parent from the effective date. A row that
 * leaves out the parent keeps it, and null takes the unit out of the hierarchy.
 */
final class ParentImport {

    static final String ORG_UNIT_PARENTS = "orgUnitParents";

    private final OrgUnitService orgUnitService;
    private final OrgUnitStore orgUnits;
    private final ExternalIdService externalIds;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();
    private final Runnable rowDone;

    ParentImport(OrgUnitService orgUnitService, OrgUnitStore orgUnits, ExternalIdService externalIds,
            String effectiveDate, String defaultSource, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.orgUnitService = orgUnitService;
        this.orgUnits = orgUnits;
        this.externalIds = externalIds;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<OrgUnitParentRow> rows) {
        report.startList(ORG_UNIT_PARENTS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(ORG_UNIT_PARENTS, apply(EmptyValues.checked(rows.get(i))));
            } catch (FuhitoException e) {
                report.error(ORG_UNIT_PARENTS, i, e);
            }
            rowDone.run();
        }
    }

    private Outcome apply(OrgUnitParentRow row) {
        String hierarchy = Checks.code("hierarchy", row.hierarchy());
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        if (row.orgUnitExternalId() != null && row.orgUnitId() != null) {
            throw FuhitoException.invalid("import.org_unit_twice",
                    Map.of("orgUnitExternalId", row.orgUnitExternalId(), "orgUnitId", row.orgUnitId()));
        }
        if (row.orgUnitExternalId() == null && row.orgUnitId() == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "orgUnitExternalId"));
        }
        String unitId = row.orgUnitId() != null ? known(row.orgUnitId(), "import.id_unknown", "id")
                : externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.orgUnitExternalId())
                        .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                                Map.of("orgUnitExternalId", row.orgUnitExternalId())));
        // A parent keeps no source.
        ImportMatch.source(unitId, null, row.source(), row.orgUnitExternalId(), row.parentExternalId());
        if (!seen.add(hierarchy + "\u0000" + unitId)) {
            throw FuhitoException.invalid("import.duplicate_row",
                    Map.of("externalId", Objects.requireNonNullElse(row.orgUnitExternalId(), unitId)));
        }
        OrgUnitState before = orgUnitService.get(unitId, effectiveDate, hierarchy);
        // A row that leaves out the parent keeps the stored one.
        boolean keeps = !row.gives("parentExternalId", row.parentExternalId())
                && !row.gives("parentId", row.parentId());
        String parentId = keeps ? before.parentId() : parentId(row, source);
        if (Objects.equals(before.parentId(), parentId)) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfOrgUnit(false, "/parent",
                orgUnitService.move(unitId, parentId, effectiveDate, hierarchy)));
        return Outcome.CHANGED;
    }

    // Null for the parent takes the unit out of the hierarchy.
    private String parentId(OrgUnitParentRow row, String source) {
        if (row.parentExternalId() != null && row.parentId() != null) {
            throw FuhitoException.invalid("import.parent_twice",
                    Map.of("parentExternalId", row.parentExternalId(), "parentId", row.parentId()));
        }
        if (row.parentId() != null) {
            return known(row.parentId(), "import.parent_id_unknown", "parentId");
        }
        if (row.parentExternalId() == null) {
            return null;
        }
        return externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.parentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.parent_unknown",
                        Map.of("parentExternalId", row.parentExternalId())));
    }

    private String known(String id, String error, String field) {
        if (orgUnits.findUnit(id).isEmpty()) {
            throw FuhitoException.invalid(error, Map.of(field, id));
        }
        return id;
    }
}

package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.service.ImportDocument.UnitRoleRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The roles over org units of one document. A row is matched by employment, org unit, and role, or by ID.
 * A row that changes whether the role covers the units below ends the stored role and starts a new one on the date.
 */
final class UnitRoleImport {

    static final String UNIT_ROLES = "unitRoles";

    private final UnitRoleService roles;
    private final ImportService.People people;
    private final OrgUnitStore orgUnits;
    private final LocalDate date;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();

    UnitRoleImport(UnitRoleService roles, ImportService.People people, OrgUnitStore orgUnits, String effectiveDate,
            String defaultSource, ImportReport report) {
        this.roles = roles;
        this.people = people;
        this.orgUnits = orgUnits;
        this.date = Checks.date("effectiveDate", effectiveDate);
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<UnitRoleRow> rows) {
        report.startList(UNIT_ROLES);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(UNIT_ROLES, apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(UNIT_ROLES, i, e);
            }
        }
    }

    private Outcome apply(UnitRoleRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        Optional<UnitRoleService.View> byId = row.id() == null ? Optional.empty() : Optional.of(roles.find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id()))));
        String employmentId = employmentId(row, source, byId);
        String orgUnitId = orgUnitId(row, source, byId);
        String role = row.role() != null ? row.role() : byId.map(UnitRoleService.View::code).orElseThrow(
                () -> FuhitoException.invalid("invalid.required", Map.of("field", "role")));
        if (byId.isPresent() && !(byId.get().role().employmentId().equals(employmentId)
                && byId.get().role().orgUnitId().equals(orgUnitId) && byId.get().code().equals(role))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", row.id(), "externalId", role));
        }
        if (!seen.add(employmentId + "\u0000" + orgUnitId + "\u0000" + role)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", role));
        }
        // The role of the date, or the first one after it for a document that comes again.
        Optional<UnitRoleService.View> stored = byId.isPresent() ? byId : roles.ofEmployment(employmentId).stream()
                .filter(view -> view.role().orgUnitId().equals(orgUnitId) && role.equals(view.code()))
                .filter(view -> view.role().period().contains(date) || view.role().period().from().isAfter(date))
                .min(Comparator.comparing(view -> view.role().period().from()));
        boolean below = Boolean.TRUE.equals(row.includesDescendants());
        if (stored.isEmpty()) {
            report.write(new Write.OfUnitRole(true, "", roles.add(employmentId, orgUnitId, role, below,
                    row.validFrom() == null ? date.toString() : row.validFrom(), row.validTo())));
            return Outcome.CREATED;
        }
        UnitRole before = stored.get().role();
        String end = row.validTo() != null ? row.validTo() : Objects.toString(before.period().to(), null);
        if (before.includesDescendants() != below) {
            report.write(new Write.OfUnitRole(false, "/end", roles.end(before.id(), date.toString())));
            report.write(new Write.OfUnitRole(true, "", roles.add(employmentId, orgUnitId, role, below,
                    date.toString(), end)));
            return Outcome.CHANGED;
        }
        if (row.validTo() == null || row.validTo().equals(Objects.toString(before.period().to(), null))) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfUnitRole(false, "/end", roles.end(before.id(), row.validTo())));
        return Outcome.CHANGED;
    }

    private String employmentId(UnitRoleRow row, String source, Optional<UnitRoleService.View> byId) {
        if (row.employmentExternalId() != null && row.employmentId() != null) {
            throw FuhitoException.invalid("import.employment_twice", Map.of(
                    "employmentExternalId", row.employmentExternalId(), "employmentId", row.employmentId()));
        }
        if (row.employmentId() != null) {
            return row.employmentId();
        }
        if (row.employmentExternalId() == null) {
            return byId.map(view -> view.role().employmentId()).orElseThrow(() -> FuhitoException.invalid(
                    "invalid.required", Map.of("field", "employmentExternalId")));
        }
        return people.employments().findByExternalId(source, row.employmentExternalId()).map(Employment::id)
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", row.employmentExternalId())));
    }

    private String orgUnitId(UnitRoleRow row, String source, Optional<UnitRoleService.View> byId) {
        if (row.orgUnitExternalId() != null && row.orgUnitId() != null) {
            throw FuhitoException.invalid("import.org_unit_twice",
                    Map.of("orgUnitExternalId", row.orgUnitExternalId(), "orgUnitId", row.orgUnitId()));
        }
        if (row.orgUnitId() != null) {
            return row.orgUnitId();
        }
        if (row.orgUnitExternalId() == null) {
            return byId.map(view -> view.role().orgUnitId()).orElseThrow(() -> FuhitoException.invalid(
                    "invalid.required", Map.of("field", "orgUnitExternalId")));
        }
        return orgUnits.findUnitByExternalId(source, row.orgUnitExternalId()).map(OrgUnit::id)
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", row.orgUnitExternalId())));
    }
}

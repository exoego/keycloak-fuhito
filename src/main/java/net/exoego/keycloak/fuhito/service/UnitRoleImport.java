package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.service.ImportDocument.UnitRoleRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The roles over org units of one document. A row is matched by employment, org unit, and role, or by ID.
 * Whether a role covers the units below does not change. A row with another value is a new role, which fails while
 * the stored one holds on the same dates, as in {@code POST /unit-roles}.
 */
final class UnitRoleImport {

    static final String UNIT_ROLES = "unitRoles";

    private record Role(UnitRoleRow row, String employmentId, String orgUnitId, String code, boolean below,
            Optional<UnitRoleService.View> byId) {
    }

    private final UnitRoleService roles;
    private final ExternalIdService externalIds;
    private final LocalDate date;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();

    UnitRoleImport(UnitRoleService roles, ExternalIdService externalIds, String effectiveDate, String defaultSource,
            ImportReport report) {
        this.roles = roles;
        this.externalIds = externalIds;
        this.date = Checks.date("effectiveDate", effectiveDate);
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<UnitRoleRow> rows) {
        report.startList(UNIT_ROLES);
        // New roles go last, so that the row that ends a role can come before or after the row of the next one.
        Map<Integer, Role> added = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                Role role = resolve(Checks.required("row", rows.get(i)));
                Optional<UnitRole> stored = stored(role).map(UnitRoleService.View::role)
                        .filter(before -> before.includesDescendants() == role.below());
                if (stored.isEmpty()) {
                    added.put(i, role);
                } else {
                    report.count(UNIT_ROLES, change(stored.get(), role.row()));
                }
            } catch (FuhitoException e) {
                report.error(UNIT_ROLES, i, e);
            }
        }
        for (Map.Entry<Integer, Role> entry : added.entrySet()) {
            try {
                report.count(UNIT_ROLES, add(entry.getValue()));
            } catch (FuhitoException e) {
                report.error(UNIT_ROLES, entry.getKey(), e);
            }
        }
    }

    private Role resolve(UnitRoleRow row) {
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
        boolean below = row.includesDescendants() != null ? row.includesDescendants()
                : byId.map(view -> view.role().includesDescendants()).orElse(false);
        if (byId.isPresent() && byId.get().role().includesDescendants() != below) {
            throw FuhitoException.conflict("import.unit_role_mismatch", Map.of("id", row.id()));
        }
        if (!seen.add(employmentId + "\u0000" + orgUnitId + "\u0000" + role + "\u0000" + below)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", role));
        }
        return new Role(row, employmentId, orgUnitId, role, below, byId);
    }

    // The role of the date, or the first one after it for a document that comes again.
    private Optional<UnitRoleService.View> stored(Role role) {
        return role.byId().isPresent() ? role.byId() : roles.ofEmployment(role.employmentId()).stream()
                .filter(view -> view.role().orgUnitId().equals(role.orgUnitId()) && role.code().equals(view.code()))
                .filter(view -> view.role().period().contains(date) || view.role().period().from().isAfter(date))
                .min(Comparator.comparing(view -> view.role().period().from()));
    }

    private Outcome change(UnitRole before, UnitRoleRow row) {
        if (row.validTo() == null || row.validTo().equals(Objects.toString(before.period().to(), null))) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfUnitRole(false, "/end", roles.end(before.id(), row.validTo())));
        return Outcome.CHANGED;
    }

    private Outcome add(Role role) {
        UnitRoleRow row = role.row();
        report.write(new Write.OfUnitRole(true, "", roles.add(role.employmentId(), role.orgUnitId(), role.code(),
                role.below(), row.validFrom() == null ? date.toString() : row.validFrom(), row.validTo())));
        return Outcome.CREATED;
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
        return externalIds.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.employmentExternalId())
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
        return externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.orgUnitExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", row.orgUnitExternalId())));
    }
}

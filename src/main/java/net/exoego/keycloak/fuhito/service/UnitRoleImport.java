package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.service.ImportDocument.UnitRoleRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The roles over org units of one document. A row is matched by source and external ID, or by ID. The employment,
 * the org unit, the role, whether it covers the units below, and the start do not change for a stored role. A new
 * role fails while another role of the same employment, org unit, and role holds on the same dates, as in
 * {@code POST /unit-roles}.
 */
final class UnitRoleImport {

    static final String UNIT_ROLES = "unitRoles";

    private record Role(UnitRoleRow row, String source, String employmentId, String orgUnitId, String code,
            boolean below, Optional<UnitRole> stored) {
    }

    private final UnitRoleService roles;
    private final ExternalIdService externalIds;
    private final LocalDate date;
    private final String defaultSource;
    private final ImportReport report;
    private final ImportMatch match;
    private final Runnable rowDone;

    UnitRoleImport(UnitRoleService roles, ExternalIdService externalIds, String effectiveDate, String defaultSource,
            ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.roles = roles;
        this.externalIds = externalIds;
        this.date = Checks.date("effectiveDate", effectiveDate);
        this.defaultSource = defaultSource;
        this.report = report;
        this.match = new ImportMatch(externalIds, ExternalId.Kind.UNIT_ROLE);
    }

    void run(List<UnitRoleRow> rows) {
        report.startList(UNIT_ROLES);
        // New roles go last, so that the row that ends a role can come before or after the row of the next one.
        Map<Integer, Role> added = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                Role role = resolve(Checks.required("row", rows.get(i)));
                if (role.stored().isEmpty()) {
                    added.put(i, role);
                } else {
                    report.count(UNIT_ROLES, change(role.stored().get(), role.row()));
                }
            } catch (FuhitoException e) {
                report.error(UNIT_ROLES, i, e);
            }
            rowDone.run();
        }
        for (Map.Entry<Integer, Role> entry : added.entrySet()) {
            try {
                report.count(UNIT_ROLES, add(entry.getValue()));
            } catch (FuhitoException e) {
                report.error(UNIT_ROLES, entry.getKey(), e);
            }
            rowDone.run();
        }
    }

    private Role resolve(UnitRoleRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        Optional<UnitRoleService.View> stored = match.stored(source, row.id(), row.externalId(),
                id -> roles.find(id).isPresent()).flatMap(roles::find);
        String employmentId = employmentId(row, source, stored);
        String orgUnitId = orgUnitId(row, source, stored);
        String role = row.role() != null ? row.role() : stored.map(UnitRoleService.View::code).orElseThrow(
                () -> FuhitoException.invalid("invalid.required", Map.of("field", "role")));
        boolean below = row.includesDescendants() != null ? row.includesDescendants()
                : stored.map(view -> view.role().includesDescendants()).orElse(false);
        stored.ifPresent(view -> {
            String id = view.role().id();
            ImportMatch.fixed(id, "employmentId", view.role().employmentId(), employmentId);
            ImportMatch.fixed(id, "orgUnitId", view.role().orgUnitId(), orgUnitId);
            ImportMatch.fixed(id, "role", view.code(), role);
            ImportMatch.fixed(id, "includesDescendants", view.role().includesDescendants(), below);
            ImportMatch.start(id, view.role().period().from(), row.validFrom());
        });
        return new Role(row, source, employmentId, orgUnitId, role, below, stored.map(UnitRoleService.View::role));
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
        report.write(new Write.OfUnitRole(true, "", match.create(role.source(), row.externalId(),
                () -> roles.add(role.employmentId(), role.orgUnitId(), role.code(), role.below(),
                        row.validFrom() == null ? date.toString() : row.validFrom(), row.validTo()),
                view -> view.role().id())));
        return Outcome.CREATED;
    }

    private String employmentId(UnitRoleRow row, String source, Optional<UnitRoleService.View> stored) {
        if (row.employmentExternalId() != null && row.employmentId() != null) {
            throw FuhitoException.invalid("import.employment_twice", Map.of(
                    "employmentExternalId", row.employmentExternalId(), "employmentId", row.employmentId()));
        }
        if (row.employmentId() != null) {
            return row.employmentId();
        }
        if (row.employmentExternalId() == null) {
            return stored.map(view -> view.role().employmentId()).orElseThrow(() -> FuhitoException.invalid(
                    "invalid.required", Map.of("field", "employmentExternalId")));
        }
        return externalIds.resourceOf(ExternalId.Kind.EMPLOYMENT, source, row.employmentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", row.employmentExternalId())));
    }

    private String orgUnitId(UnitRoleRow row, String source, Optional<UnitRoleService.View> stored) {
        if (row.orgUnitExternalId() != null && row.orgUnitId() != null) {
            throw FuhitoException.invalid("import.org_unit_twice",
                    Map.of("orgUnitExternalId", row.orgUnitExternalId(), "orgUnitId", row.orgUnitId()));
        }
        if (row.orgUnitId() != null) {
            return row.orgUnitId();
        }
        if (row.orgUnitExternalId() == null) {
            return stored.map(view -> view.role().orgUnitId()).orElseThrow(() -> FuhitoException.invalid(
                    "invalid.required", Map.of("field", "orgUnitExternalId")));
        }
        return externalIds.resourceOf(ExternalId.Kind.ORG_UNIT, source, row.orgUnitExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", row.orgUnitExternalId())));
    }
}

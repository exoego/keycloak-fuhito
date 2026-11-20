package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.PersonRow;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The people, the employments, and the assignments of one document. Each list reads what its rows name with one
 * read for each kind, checks its rows in their order, and stores its new records together. A row that changes a
 * stored record first stores the new records of the rows before it, so that its checks see them.
 */
final class PeopleImport {

    final ImportService.People people;
    final ExternalIdService externalIds;
    final OrgUnitStore orgUnits;
    final String effectiveDate;
    final String defaultSource;
    final ImportReport report;
    final Runnable rowDone;

    PeopleImport(ImportService.People people, ExternalIdService externalIds, OrgUnitStore orgUnits,
            String effectiveDate, String defaultSource, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.people = people;
        this.externalIds = externalIds;
        this.orgUnits = orgUnits;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void people(List<PersonRow> rows) {
        new PersonImport(this).run(rows);
    }

    void employments(List<EmploymentRow> rows) {
        new EmploymentImport(this).run(rows);
    }

    void assignments(List<AssignmentRow> rows) {
        new AssignmentImport(this).run(rows);
    }

    String sourceOf(String source) {
        return source == null ? defaultSource : source;
    }

    // The records that a row names by ID and by an ID of another system.
    Set<String> named(ListIds ids, String id, ExternalId.Kind kind, String source, String externalId) {
        Set<String> named = new HashSet<>();
        if (id != null) {
            named.add(id);
        }
        if (externalId != null) {
            ids.resourceOf(kind, sourceOf(source), externalId).ifPresent(named::add);
        }
        return named;
    }

    // A row with an ID is known by it. Any other row needs an external ID.
    void requireFirst(Set<String> seen, String source, String externalId, String id) {
        if (id != null) {
            if (!seen.add("\\u0000id\\u0000" + id)) {
                throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", id));
            }
            return;
        }
        Checks.required("externalId", externalId);
        if (!seen.add(sourceOf(source) + "\\u0000" + externalId)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", externalId));
        }
    }

    // A row with an ID changes that record. An external ID beside it must be one of that record.
    static void requireNames(ListIds ids, ExternalId.Kind kind, String id, String source, String externalId) {
        if (externalId != null && !ids.resourceOf(kind, source, externalId).equals(Optional.of(id))) {
            throw FuhitoException.invalid("import.id_mismatch", Map.of("id", id, "externalId", externalId));
        }
    }

    String userId(String userId, String username) {
        if (userId != null && username != null) {
            throw FuhitoException.invalid("import.user_twice", Map.of("field", "userId"));
        }
        if (userId != null) {
            return userId;
        }
        String name = Checks.required("username", username);
        return people.users().findIdByUsername(name)
                .orElseThrow(() -> FuhitoException.invalid("import.username_unknown", Map.of("username", name)));
    }

    static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ImportDocument.ExternalIdRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The IDs that systems have for resources, of one document. A row is matched by type of resource, resource, and
 * source. Another external ID replaces the stored one, and null removes it. A row that leaves it out keeps it.
 */
final class ExternalIdImport {

    static final String EXTERNAL_IDS = "externalIds";

    private final ExternalIdService externalIds;
    private final String defaultSource;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();
    private final Runnable rowDone;

    ExternalIdImport(ExternalIdService externalIds, String defaultSource, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.externalIds = externalIds;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void run(List<ExternalIdRow> rows) {
        report.startList(EXTERNAL_IDS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(EXTERNAL_IDS, apply(EmptyValues.checked(rows.get(i))));
            } catch (FuhitoException e) {
                report.error(EXTERNAL_IDS, i, e);
            }
            rowDone.run();
        }
    }

    private Outcome apply(ExternalIdRow row) {
        String kind = Checks.required("resourceType", row.resourceType());
        String resourceId = Checks.required("resourceId", row.resourceId());
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String value = row.externalId();
        if (!seen.add(kind + "\u0000" + resourceId + "\u0000" + source)) {
            throw FuhitoException.invalid("import.duplicate_row",
                    Map.of("externalId", Objects.requireNonNullElse(value, resourceId)));
        }
        Optional<ExternalIdService.View> stored = externalIds.list(kind, resourceId).stream()
                .filter(view -> view.source().equals(source))
                .findFirst();
        if (!row.gives("externalId", value)) {
            return Outcome.UNCHANGED;
        }
        if (value == null) {
            if (stored.isEmpty()) {
                return Outcome.UNCHANGED;
            }
            report.write(ImportReport.Write.Removed.externalId(
                    externalIds.remove(kind, resourceId, source).rowId()));
            return Outcome.CHANGED;
        }
        if (stored.isPresent() && stored.get().externalId().equals(value)) {
            return Outcome.UNCHANGED;
        }
        ExternalIdService.Put put = externalIds.put(kind, resourceId, source, value);
        report.write(new Write.OfExternalId(put.created(), put.view()));
        return put.created() ? Outcome.CREATED : Outcome.CHANGED;
    }
}

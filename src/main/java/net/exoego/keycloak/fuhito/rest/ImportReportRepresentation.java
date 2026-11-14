package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.ImportReport;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ImportReport",
        description = "What an import did, or would do.",
        requiredProperties = {"dryRun", "applied", "counts", "errors"})
public record ImportReportRepresentation(
        @Schema(description = "True when the request asked for a dry run.")
        boolean dryRun,
        @Schema(description = "True when the rows were written. It is false for a dry run and when a row failed.")
        boolean applied,
        @Schema(description = "The number of rows by outcome for each list of the document.")
        Map<String, ImportReport.Counts> counts,
        @Schema(description = "The rows that could not be applied. When this is not empty, nothing was written.")
        List<RowError> errors) {

    @Schema(
            name = "ImportRowError",
            description = "A row that could not be applied.",
            requiredProperties = {"list", "row", "error", "message"})
    public record RowError(
            @Schema(description = "The name of the list in the document.")
            String list,
            @Schema(description = "The position of the row in its list, counted from 0.")
            int row,
            @Schema(description = "The line number in the CSV file, counted from 1. Only a CSV import has it.")
            Integer line,
            @Schema(description = "The stable error code.")
            String error,
            @Schema(description = "The values that the message refers to.")
            Map<String, String> params,
            @Schema(description = "The message for people.")
            String message) {
    }

    static ImportReportRepresentation of(ImportReport report, boolean dryRun, Locale locale) {
        List<RowError> errors = report.errors().stream()
                .map(e -> new RowError(e.list(), e.row(), null, e.error().code(), e.error().params(),
                        Messages.format(e.error().code(), e.error().params(), locale)))
                .toList();
        return new ImportReportRepresentation(dryRun, !dryRun && errors.isEmpty(), report.counts(), errors);
    }

    static ImportReportRepresentation of(CsvImportService.Result result, boolean dryRun, Locale locale) {
        List<RowError> errors = result.errors().stream()
                .map(e -> new RowError(e.list(), e.row(), e.line(), e.error().code(), e.error().params(),
                        Messages.format(e.error().code(), e.error().params(), locale)))
                .toList();
        return new ImportReportRepresentation(dryRun, !dryRun && errors.isEmpty(), result.report().counts(), errors);
    }
}

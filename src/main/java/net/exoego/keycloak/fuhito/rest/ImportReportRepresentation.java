package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.ImportReport;

/**
 * What an import did, or would do.
 *
 * @param dryRun true when the request asked for a dry run
 * @param applied true when the rows were written. It is false for a dry run and when a row failed.
 * @param counts the number of rows by outcome for each list of the document
 * @param errors the rows that could not be applied. When this is not empty, nothing was written.
 */
public record ImportReportRepresentation(boolean dryRun, boolean applied, Map<String, ImportReport.Counts> counts,
        List<RowError> errors) {

    /**
     * A row that could not be applied.
     *
     * @param list the name of the list in the document
     * @param row the position of the row in its list, counted from 0
     * @param line the line number in the CSV file, counted from 1. Only a CSV import has it.
     * @param error the stable error code
     * @param params the values that the message refers to
     * @param message the message for people
     */
    public record RowError(String list, int row, Integer line, String error, Map<String, String> params,
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

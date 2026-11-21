package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.CsvExportService;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.ImportDocument;
import net.exoego.keycloak.fuhito.service.ImportReport;
import net.exoego.keycloak.fuhito.service.ImportService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Imports into one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.BULK)
public class ImportsResource {

    private static final Map<String, String> CSV_LISTS = Map.ofEntries(Map.entry("ref-kinds", "refKinds"),
            Map.entry("attribute-definitions", "attributeDefinitions"), Map.entry("ref-items", "refItems"),
            Map.entry("hierarchies", "hierarchies"), Map.entry("org-unit-parents", "orgUnitParents"),
            Map.entry("org-units", "orgUnits"), Map.entry("employments", "employments"),
            Map.entry("assignments", "assignments"), Map.entry("people", "people"),
            Map.entry("reporting-lines", "reportingLines"), Map.entry("unit-roles", "unitRoles"),
            Map.entry("qualifications", "qualifications"), Map.entry("external-ids", "externalIds"),
            Map.entry("change-orders", "changeOrders"));

    private final RequestContext context;
    private final ImportService service;
    private final CsvImportService csvService;
    private final CsvExportService csvExport;
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param csvService the import of CSV files into the realm of the request
     * @param csvExport the CSV files of the lists of the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public ImportsResource(RequestContext context, ImportService service, CsvImportService csvService,
            CsvExportService csvExport, ExternalIdService externalIds) {
        this.externalIds = externalIds;
        this.context = context;
        this.service = service;
        this.csvService = csvService;
        this.csvExport = csvExport;
    }

    @GET
    @Path("csv/{list}")
    @Produces("text/csv")
    @Operation(
            operationId = "exportCsv",
            summary = "Returns a list on a date as a CSV that the import loads again.",
            description = "The lists are the same as for the import. A row of a thing that has an ID has the "
                    + "column `id`, so that a row of a thing with no external ID comes back too. The columns "
                    + "`source` and `externalId` hold the system that made the thing and the ID that it has there. "
                    + "A thing that keeps no system that made it, such as a reporting line, has the first of its IDs "
                    + "by the name of the system. "
                    + "The kinds, the definitions of attributes, the hierarchies, the people, the IDs of other "
                    + "systems, and the change orders have no date, so they take no `asOf`. "
                    + "The reference items have the names of the date.")
    @APIResponse(responseCode = "200", description = "The CSV, in UTF-8 with a byte order mark.")
    @BadRequest
    @ApiErrors
    public Response exportCsv(
            @Parameter(
                    description = "The kind of rows.",
                    schema = @Schema(enumeration = {"ref-kinds", "attribute-definitions", "ref-items", "hierarchies",
                        "org-units", "org-unit-parents", "people", "employments", "assignments", "reporting-lines",
                        "unit-roles", "qualifications", "external-ids", "change-orders"}))
            @PathParam("list") String list,
            @Parameter(
                    description = """
                            The date of the state. The default is today in the time zone of the realm. \
                            For `qualifications`, the default is every date. \
                            The lists `ref-kinds`, `attribute-definitions`, `hierarchies`, `people`, `external-ids`, \
                            and `change-orders` have no date. \
                            A date with one of them fails with 400 and `csv.export_no_date`.""",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "`ja` for the Japanese names of the columns. The default is the English ones.",
                    schema = @Schema(enumeration = {"en", "ja"}))
            @QueryParam("header") String header) {
        context.requireRead();
        return Calls.run(context, () -> Response.ok(csvExport.write(list, asOf, "ja".equals(header)))
                .type("text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + list + ".csv\"")
                .build());
    }

    @POST
    @Path("csv/{list}")
    @Consumes("text/csv")
    @Operation(
            operationId = "applyCsvImport",
            summary = "Loads one CSV file in one transaction.",
            description = "The first row has the column names, in English or in Japanese. A blank cell keeps the "
                    + "stored value, as a key that a JSON row leaves out does. The cell `#CLEAR` removes it, as "
                    + "`null` in a JSON row does. A cell of a list separates its items with `|`. "
                    + "In an item, `\\|` is a `|` and `\\\\` is a `\\`. The `charset` of the content type says how "
                    + "the file is encoded. The default is UTF-8. fuhito does not guess the encoding. A file that is "
                    + "not valid in its charset fails with `csv.bad_encoding`, with the line and the offset of the "
                    + "first bad bytes. So does a file that starts with `FF FE` or `FE FF`, or has a `00` byte on its "
                    + "first line, unless the charset is UTF-16 or UTF-32. Such a file is most likely UTF-16. An "
                    + "unknown charset fails with `csv.bad_charset`. A `00` byte after the first line is NUL in a "
                    + "cell, which fails with `invalid.character` and names the column. A number has the form of a "
                    + "number of JSON, with only the digits 0 to 9, no `+` in front, no `0` in front of another "
                    + "digit, and digits on both sides of a point. A cell in another form, such as `５`, `+5`, `05`, "
                    + "or `.5`, or a number that the column cannot take, fails the row with `csv.bad_number`, which "
                    + "names the column. Such a number is a `displayOrder` that is no whole number from "
                    + "-2147483648 to 2147483647, or a number of an attribute of the type `NUMBER` that a 64-bit "
                    + "floating point number does not give back the same, such as `1e400` or `1e999999999`, as in a "
                    + "JSON import.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "200",
            description = "The rows were written, or the request was a dry run without errors.")
    @APIResponse(responseCode = "400", ref = "ImportFailed")
    @ApiErrors
    public ImportReportRepresentation applyCsv(
            @Parameter(
                    description = "The kind of rows.",
                    schema = @Schema(enumeration = {"ref-kinds", "attribute-definitions", "ref-items", "hierarchies",
                        "org-units", "org-unit-parents", "people", "employments", "assignments", "reporting-lines",
                        "unit-roles", "qualifications", "external-ids", "change-orders"}))
            @PathParam("list") String list,
            @Parameter(description = "The date of the state.", required = true, schema = @Schema(format = "date"))
            @QueryParam("effectiveDate") String effectiveDate,
            @Parameter(
                    description = "True to do all checks and write nothing.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("dryRun") boolean dryRun,
            @Parameter(
                    description = "The name of the system that the rows come from.",
                    schema = @Schema(defaultValue = "import"))
            @QueryParam("source") String source,
            @HeaderParam("Content-Type") String contentType,
            byte[] file) {
        context.requireBulkWrite();
        return Calls.run(context, () -> {
            String name = CSV_LISTS.get(list);
            if (name == null) {
                throw FuhitoException.invalid("invalid.choice", Map.of("field", "list", "value", list));
            }
            // The framework reads text as UTF-8 whatever the request says, so the bytes are decoded here.
            String csv = CsvImportService.decode(Checks.required("body", file), charset(contentType));
            var result = csvService.apply(name, csv, effectiveDate, dryRun, source);
            return finish(result.report(), ImportReportRepresentation.of(result, dryRun, context.locale()), dryRun);
        });
    }

    @POST
    @Operation(
            operationId = "applyImport",
            summary = "Loads many rows in one transaction.",
            description = "All rows are written, or none. A dry run does all checks and writes nothing. A row of a "
                    + "stored thing changes the values that it gives. A key that a row leaves out keeps the stored "
                    + "value, and `null` clears it. An object such as `names` or `attributes` merges by key in the "
                    + "same way. An empty text, a text of only spaces, and an empty array fail with "
                    + "`invalid.blank`. A text with NUL (U+0000) or half of a surrogate pair fails with "
                    + "`invalid.character`, as the database cannot store it. A document that gives one key twice "
                    + "in one object fails as a whole with `invalid.duplicate_key`, which names the key with its "
                    + "path, such as `orgUnits[1].names.ja`. A key that the document does not have fails as a whole "
                    + "with `invalid.unknown_key`, as in a write. It names the key with its path, such as "
                    + "`employments[2].employerExternalID`. This holds for the keys of the document, of each row, "
                    + "and of the names of one language, as a CSV column that its list does not have fails with "
                    + "`csv.bad_column`. The keys of `attributes` are the names of attributes, and their "
                    + "definitions check them. A value of another JSON type than its field fails as a whole with "
                    + "`invalid.type`, as in a write, such as `\"3\"` or `3.7` for `displayOrder`. It names the "
                    + "field with its path, such as `refItems[0].displayOrder`, and the type of the field. A whole "
                    + "number outside the range of its field fails as a whole with `invalid.out_of_range`, which "
                    + "names the field with its path and the limits in `min` and `max`. A decimal number is read as "
                    + "it is written, as in a write. So `50.000000000000001` for `workPercent` fails the row with "
                    + "`invalid.percent`, and a value of the type `NUMBER` in `attributes` that a 64-bit floating "
                    + "point number does not give back the same fails the row with `attribute.bad_value`. A number "
                    + "whose exponent is too large or too small to read fails as a whole with `invalid.number`. A "
                    + "new thing has nothing stored, so a missing key and `null` both give no value. The lists "
                    + "are applied in the order change orders, kinds of reference data, definitions of attributes, "
                    + "reference items, hierarchies, org units, parents in hierarchies, people, qualifications, "
                    + "employments, roles over org units, assignments, reporting lines, IDs of other systems.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "200",
            description = "The rows were written, or the request was a dry run without errors.")
    @APIResponse(responseCode = "400", ref = "ImportFailed")
    @ApiErrors
    public ImportReportRepresentation apply(ImportDocument body) {
        context.requireBulkWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            ImportReport report = service.apply(body);
            return finish(report, ImportReportRepresentation.of(report, body.dryRun(), context.locale()),
                    body.dryRun());
        });
    }

    private static String charset(String contentType) {
        // The endpoint takes only text/csv, so the header is always there.
        return MediaType.valueOf(contentType).getParameters().get("charset");
    }

    private ImportReportRepresentation finish(ImportReport report, ImportReportRepresentation representation,
            boolean dryRun) {
        if (!representation.errors().isEmpty()) {
            throw context.failure(json(Response.Status.BAD_REQUEST, representation));
        }
        if (dryRun) {
            // The failure rolls back everything that the checks wrote. The response is still a success.
            throw context.failure(json(Response.Status.OK, representation));
        }
        Writes.recordAll(context, externalIds, report.writes());
        return representation;
    }

    private static Response json(Response.Status status, Object entity) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
    }
}

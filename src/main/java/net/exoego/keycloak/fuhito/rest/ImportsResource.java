package net.exoego.keycloak.fuhito.rest;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
            Map.entry("qualifications", "qualifications"));

    private final RequestContext context;
    private final ImportService service;
    private final CsvImportService csvService;
    private final CsvExportService csvExport;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param csvService the import of CSV files into the realm of the request
     * @param csvExport the CSV files of the lists of the realm of the request
     */
    public ImportsResource(RequestContext context, ImportService service, CsvImportService csvService,
            CsvExportService csvExport) {
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
                    + "column `id`, so that a row of a thing with no external ID comes back too. The kinds, the "
                    + "definitions of attributes, the reference items, the hierarchies, and the people have no "
                    + "date.")
    @APIResponse(responseCode = "200", description = "The CSV, in UTF-8 with a byte order mark.")
    @BadRequest
    @ApiErrors
    public Response exportCsv(
            @Parameter(
                    description = "The kind of rows.",
                    schema = @Schema(enumeration = {"ref-kinds", "attribute-definitions", "ref-items", "hierarchies",
                        "org-units", "org-unit-parents", "people", "employments", "assignments", "reporting-lines",
                        "unit-roles", "qualifications"}))
            @PathParam("list") String list,
            @Parameter(
                    description = "The date of the state. The default is today in the time zone of the realm.",
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
                    + "stored value. The cell `#CLEAR` removes it. The `charset` of the content type says how the "
                    + "file is encoded. The default is UTF-8.")
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
                        "unit-roles", "qualifications"}))
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
        context.requireWrite();
        return Calls.run(context, () -> {
            String name = CSV_LISTS.get(list);
            if (name == null) {
                throw FuhitoException.invalid("invalid.choice", Map.of("field", "list", "value", list));
            }
            // The framework reads text as UTF-8 whatever the request says, so the bytes are decoded here.
            String csv = new String(Checks.required("body", file), charset(contentType));
            var result = csvService.apply(name, csv, effectiveDate, dryRun, source);
            return finish(result.report(), ImportReportRepresentation.of(result, dryRun, context.locale()), dryRun);
        });
    }

    @POST
    @Operation(
            operationId = "applyImport",
            summary = "Loads many rows in one transaction.",
            description = "All rows are written, or none. A dry run does all checks and writes nothing. The lists "
                    + "are applied in the order kinds of reference data, definitions of attributes, reference "
                    + "items, hierarchies, org units, parents in hierarchies, people, qualifications, employments, "
                    + "roles over org units, assignments, reporting lines.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "200",
            description = "The rows were written, or the request was a dry run without errors.")
    @APIResponse(responseCode = "400", ref = "ImportFailed")
    @ApiErrors
    public ImportReportRepresentation apply(ImportDocument body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            ImportReport report = service.apply(body);
            return finish(report, ImportReportRepresentation.of(report, body.dryRun(), context.locale()),
                    body.dryRun());
        });
    }

    private static Charset charset(String contentType) {
        // The endpoint takes only text/csv, so the header is always there.
        String name = MediaType.valueOf(contentType).getParameters().get("charset");
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw FuhitoException.invalid("csv.bad_charset", Map.of("charset", name)).causedBy(e);
        }
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
        report.writes().forEach(write -> Writes.record(context, write));
        return representation;
    }

    private static Response json(Response.Status status, Object entity) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
    }
}

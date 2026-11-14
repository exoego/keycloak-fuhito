package net.exoego.keycloak.fuhito.rest;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.ws.rs.Consumes;
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
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.ImportDocument;
import net.exoego.keycloak.fuhito.service.ImportReport;
import net.exoego.keycloak.fuhito.service.ImportService;

/**
 * Imports into one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImportsResource {

    private static final Map<String, String> CSV_LISTS = Map.of("ref-items", "refItems", "org-units", "orgUnits",
            "employments", "employments", "assignments", "assignments");

    private final RequestContext context;
    private final ImportService service;
    private final CsvImportService csvService;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param csvService the import of CSV files into the realm of the request
     */
    public ImportsResource(RequestContext context, ImportService service, CsvImportService csvService) {
        this.context = context;
        this.service = service;
        this.csvService = csvService;
    }

    /**
     * Loads one CSV file in one transaction.
     * The first row has the column names, in English or in Japanese.
     * A blank cell keeps the stored value. The cell {@code #CLEAR} removes it.
     *
     * @param list the kind of rows. It is {@code ref-items}, {@code org-units}, {@code employments}, or
     *        {@code assignments}.
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from. The default is "import".
     * @param contentType the content type of the request. Its {@code charset} says how the file is encoded.
     *        The default is UTF-8. A file saved by a spreadsheet in Japan is often {@code Shift_JIS}.
     * @param file the bytes of the file
     * @return 200 with the report, or 400 with the report when a row failed
     */
    @POST
    @Path("csv/{list}")
    @Consumes("text/csv")
    public ImportReportRepresentation applyCsv(@PathParam("list") String list,
            @QueryParam("effectiveDate") String effectiveDate, @QueryParam("dryRun") boolean dryRun,
            @QueryParam("source") String source, @HeaderParam("Content-Type") String contentType, byte[] file) {
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

    /**
     * Loads many rows in one transaction.
     * All rows are written, or none. A dry run does all checks and writes nothing.
     *
     * @param body the effective date, the dry run flag, and the rows
     * @return 200 with the report when the rows were written or the request was a dry run without errors.
     *         400 with the report when a row failed.
     */
    @POST
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

package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.RealmExportService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The export of every stored row of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.BULK)
public class ExportResource {

    private static final Cursor.Format CURSOR = new Cursor.Format("export.v1", 1);

    private final RequestContext context;
    private final RealmExportService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the export of the realm
     */
    public ExportResource(RequestContext context, RealmExportService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Path("{list}")
    @Operation(
            operationId = "exportList",
            summary = "Reads one page of one list of every stored row of the realm, ordered by ID.",
            description = "Follow the `Link` header of each response to the end of the list. Put the lists "
                    + "together under their names in camel case, with `format`, to get the document that `POST "
                    + "/restore` takes. Rows name Keycloak users by username.")
    @APIResponse(
            responseCode = "200",
            description = "The rows of the page.",
            headers = @Header(
                    name = "Link",
                    description = "The URL of the next page, with `rel=\"next\"`. It is there when the page is "
                            + "full.",
                    schema = @Schema(type = SchemaType.STRING)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response exportList(
            @Parameter(
                    description = "The list.",
                    schema = @Schema(enumeration = {"ref-kinds", "ref-items", "attribute-definitions", "hierarchies",
                        "org-units", "people", "employments", "assignments", "reporting-lines", "unit-roles",
                        "qualifications", "external-ids", "change-orders"}))
            @PathParam("list") String list,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(description = "The cursor from the `Link` header of the page before.")
            @QueryParam("after") String after) {
        context.requireRead();
        return Calls.run(context, () -> {
            int size = Page.of(null, max).max();
            String afterId = after == null ? null : Cursor.decode(CURSOR, after).getFirst();
            RealmExportService.ExportPage page = service.page(list, afterId, size);
            Response.ResponseBuilder response = Response.ok(page.rows());
            if (size > 0 && page.rows().size() == size) {
                response.header("Link", "<" + context.nextPage(Cursor.encode(CURSOR, page.lastId()))
                        + ">; rel=\"next\"");
            }
            return response.build();
        });
    }
}

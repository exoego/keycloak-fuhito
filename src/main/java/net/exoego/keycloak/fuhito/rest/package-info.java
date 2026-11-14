/**
 * The admin REST API.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "keycloak-fuhito admin REST API",
                summary = "Organization structure and its history for each Keycloak realm.",
                version = "0.1.0",
                description = """
                        The API has no release yet and can still change.

                        Dates have the form `YYYY-MM-DD`.
                        A period includes its `validFrom` date and does not include its `validTo` date.

                        A response leaves out every field that has no value.
                        For example, an org unit with no end date has no `validTo` field.

                        A read needs the permission to view users. A write needs the permission to manage users.

                        A write belongs to a change order when its request has the query parameter `changeOrder`.

                        A failed request writes nothing.
                        """,
                license = @License(name = "Apache License, Version 2.0", identifier = "Apache-2.0")),
        externalDocs = @ExternalDocumentation(
                description = "How the extension plugs into Keycloak",
                url = "https://github.com/exoego/keycloak-fuhito/blob/main/docs/keycloak-integration.md"),
        servers = @Server(
                url = "{baseUrl}/admin/realms/{realm}/fuhito",
                variables = {
                    @ServerVariable(name = "baseUrl", defaultValue = "http://localhost:8080",
                            description = "The base URL of Keycloak."),
                    @ServerVariable(name = "realm", defaultValue = "master", description = "The name of the realm.")
                }),
        security = @SecurityRequirement(name = "bearer"),
        tags = {
            @Tag(name = Tags.GENERAL, description = "The version and the hierarchies."),
            @Tag(name = Tags.REF_ITEMS,
                    description = "The lists that a realm defines, such as unit types and occupations."),
            @Tag(name = Tags.ORG_UNITS, description = "Org units, their tree on a date, and their history."),
            @Tag(name = Tags.EMPLOYMENTS, description = "Employments of users, with their terms over time."),
            @Tag(name = Tags.ASSIGNMENTS, description = "Assignments of employments to org units."),
            @Tag(name = Tags.REPORTING_LINES, description = "Links from an assignment to a manager."),
            @Tag(name = Tags.QUALIFICATIONS, description = "Licenses and certificates of users."),
            @Tag(name = Tags.PEOPLE, description = "The people that the realm knows, with or without a Keycloak user."),
            @Tag(name = Tags.USERS, description = "What is stored about one user."),
            @Tag(name = Tags.CHANGE_ORDERS,
                    description = "Approved instructions that cause changes, and the log of every write."),
            @Tag(name = Tags.BULK, description = "Many rows in one request, and all data of the realm."),
            @Tag(name = Tags.KEYCLOAK,
                    description = "The projection into organization groups, and the sync from user attributes.")
        },
        components = @Components(
                schemas = {
                    @Schema(name = "RefKind", implementation = RefKind.class,
                            description = "The kinds of reference items. Each realm defines its own items for each kind."),
                    @Schema(name = "EmploymentStatus", implementation = EmploymentStatus.class,
                            description = "Whether a person works during a period of an employment."),
                    @Schema(name = "ReportingKind", implementation = ReportingLine.Kind.class,
                            description = """
                                    `SOLID` is the main manager. An assignment has at most one at a time.
                                    `DOTTED` is a further manager, for example of a project.
                                    """)
                },
                responses = {
                    @APIResponse(name = "BadRequest", description = "A value in the request is bad.",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(implementation = ErrorRepresentation.class))),
                    @APIResponse(name = "Unauthorized", description = "The request has no valid token."),
                    @APIResponse(name = "Forbidden", description = "The caller lacks the permission."),
                    @APIResponse(name = "NotFound", description = "The target does not exist in the realm.",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(implementation = ErrorRepresentation.class))),
                    @APIResponse(name = "Conflict",
                            description = """
                                    The request conflicts with existing data.
                                    A realm with no usable time zone also gets this status on every request.
                                    """,
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(implementation = ErrorRepresentation.class))),
                    @APIResponse(name = "ImportFailed",
                            description = """
                                    A row failed, and the body is the report with every bad row.
                                    Or the request itself is bad, and the body is an error.
                                    """,
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(oneOf = {ImportReportRepresentation.class,
                                        ErrorRepresentation.class})))
                },
                parameters = @Parameter(name = "changeOrder", in = ParameterIn.QUERY,
                        description = """
                                The ID of the change order that the write belongs to.
                                A request that names an unknown change order gets 400 and writes nothing.
                                """,
                        schema = @Schema(type = SchemaType.STRING))))
package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

                        Dates have the form `YYYY-MM-DD`, from `0001-01-01` to `9999-12-31`.
                        A date in another form or outside that range fails with 400 and `invalid.date`, which names
                        the field. This holds for every date of a write, of an import, and of a query, such as `asOf`.
                        A period includes its `validFrom` date and does not include its `validTo` date.

                        A response leaves out every field that has no value.
                        For example, an org unit with no end date has no `validTo` field.

                        A text in the body of a write must not be empty or only spaces. This holds for every field,
                        also for a name and for a text in `attributes`. Such a text fails with 400 and
                        `invalid.blank`, which names the field. An empty array fails in the same way, also a list
                        in `attributes`. A field that is left out or null has no value.

                        The database cannot store NUL (U+0000) or half of a surrogate pair, such as `"\\ud800"`.
                        A text with one fails with 400 and `invalid.character`, which names the field and the code
                        point. This holds for every text of every write, also for a key in `attributes`. Other
                        characters, such as control characters, emoji, and combining marks, are stored as sent.

                        A body that gives one key twice in one object fails with 400 and `invalid.duplicate_key`,
                        which names the key with its path, such as `names.ja` or `orgUnits[1].names.ja`. No value
                        is dropped. This holds for every body, also for an import, a restore, and the steps of a
                        reorganization.

                        A write that names one thing in two ways, such as a unit of a reorganization step by `id` and
                        by `unitRef`, fails with 400 and `invalid.twice`. The error names the two fields in `field`
                        and `otherField`. No value is dropped. An employer named by `employerId` and by
                        `externalEmployer` fails with `employment.employer_twice`.

                        A read that gives two values that do not go together, such as `asOf` and `all=true`, fails
                        with 400 and `invalid.not_together`. The error names the two in `field` and `otherField`.
                        No value is dropped.

                        A person ID and a user ID are two names of one person, and a read has both when the person
                        has a user. So a create can name a person by `personId`, by `userId`, or by both, and the
                        same holds for `managerPersonId` and `managerUserId`. Two IDs that do not name the same person
                        fail with 400 and `invalid.mismatch`, which names the two fields in `field` and `otherField`.

                        The `maxLength` of a text counts characters, as the database does, so an emoji is one
                        character. A longer text fails with 400. A text with no other rule, such as
                        `externalReference`, fails with `invalid.too_long`, which names the field and the `max`.

                        A read of the org structure needs the role `fuhito-view-structure`. Every other read needs
                        `fuhito-view-people`. A write needs `fuhito-manage`. The roles are in the client
                        `realm-management` of the realm.

                        A write belongs to a change order when its request has the query parameter `changeOrder`.

                        A write of a stored resource names the `version` of the resource that the caller read.
                        It is in the body, or in the query of a `DELETE`. A write with no version fails with 400 and
                        `version.required`. When the resource changed since the read, the write fails with 409 and
                        `version.conflict`, and the values of the error have the current version.

                        Some fields of a stored resource never change, such as the code of a hierarchy and the
                        person of a qualification. A write of the resource can leave them out, or give the stored
                        values, as a read has them. Another value fails with 400 and `invalid.value_fixed`, which
                        names the field.

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
                    description = "The lists that a realm defines, such as unit types and occupations, and the "
                            + "definitions of free attributes."),
            @Tag(name = Tags.ORG_UNITS, description = "Org units, their tree on a date, and their history."),
            @Tag(name = Tags.EMPLOYMENTS, description = "Employments of users, with their terms over time."),
            @Tag(name = Tags.ASSIGNMENTS, description = "Assignments of employments to org units."),
            @Tag(name = Tags.REPORTING_LINES, description = "Links from an assignment to a manager."),
            @Tag(name = Tags.UNIT_ROLES,
                    description = "Roles of employments over org units that are not membership."),
            @Tag(name = Tags.QUALIFICATIONS, description = "Licenses and certificates of users."),
            @Tag(name = Tags.PEOPLE, description = "The people that the realm knows, with or without a Keycloak user."),
            @Tag(name = Tags.USERS, description = "What is stored about one user."),
            @Tag(name = Tags.CHANGE_ORDERS,
                    description = "Approved instructions that cause changes, and the log of every write."),
            @Tag(name = Tags.BULK, description = "Many rows in one request."),
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
                    @APIResponse(name = "BadRequest",
                            description = """
                                    A value in the request is bad.
                                    In a write, an empty text, a text of only spaces, or an empty array gets
                                    `invalid.blank`. A text with NUL or half of a surrogate pair gets
                                    `invalid.character`. A body that gives one key twice in one object gets
                                    `invalid.duplicate_key`. A write that names one thing in two ways gets
                                    `invalid.twice`. A create with a person ID and a user ID that do not name the
                                    same person gets `invalid.mismatch`. A write of a stored resource with another
                                    value of a field that never changes gets `invalid.value_fixed`. A read with two
                                    values that do not go together gets `invalid.not_together`.
                                    """,
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
                                    A write with an old version gets it with `version.conflict`.
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

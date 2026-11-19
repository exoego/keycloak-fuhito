package net.exoego.keycloak.fuhito.rest;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.service.PersonService;

/**
 * Records the writes of a bulk operation in the event log and the change log, and finds the person of a write.
 */
final class Writes {

    private Writes() {
    }

    // A write may name a person by the ID of a Keycloak user. The person is created when the user has none.
    static String personId(RequestContext context, PersonService people, String field, String personId,
            String userId) {
        if (personId != null) {
            return personId;
        }
        if (userId == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", field));
        }
        PersonService.Resolved resolved = people.forUser(userId);
        if (resolved.created() != null) {
            // A person made for a user has no IDs of other systems yet.
            record(context, kind -> ExternalIdLists.NONE, new Write.OfPerson(resolved.created()),
                    context.nameLanguage());
        }
        return resolved.person().id();
    }

    static void record(RequestContext context, ExternalIdService externalIds, Write write) {
        recordAll(context, externalIds, List.of(write));
    }

    // The IDs of the records of all writes are read with one query for each kind of record.
    static void recordAll(RequestContext context, ExternalIdService externalIds, List<Write> writes) {
        Map<ExternalId.Kind, ExternalIdLists> ids = new EnumMap<>(ExternalId.Kind.class);
        for (ExternalId.Kind kind : ExternalId.Kind.values()) {
            ids.put(kind, externalIds.lists(kind, writes.stream()
                    .map(write -> recordOf(write, kind))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList()));
        }
        Locale language = context.nameLanguage();
        writes.forEach(write -> record(context, ids::get, write, language));
    }

    private static String recordOf(Write write, ExternalId.Kind kind) {
        return switch (write) {
            case Write.OfPerson person when kind == ExternalId.Kind.PERSON -> person.state().person().id();
            case Write.OfOrgUnit unit when kind == ExternalId.Kind.ORG_UNIT -> unit.state().unit().id();
            case Write.OfEmployment employment when kind == ExternalId.Kind.EMPLOYMENT ->
                    employment.state().employment().id();
            case Write.OfAssignment assignment when kind == ExternalId.Kind.ASSIGNMENT ->
                    assignment.state().assignmentId();
            case Write.OfQualification qualification when kind == ExternalId.Kind.QUALIFICATION ->
                    qualification.state().qualification().id();
            default -> null;
        };
    }

    private static void record(RequestContext context, Function<ExternalId.Kind, ExternalIdLists> ids,
            Write write, Locale language) {
        Object representation = switch (write) {
            case Write.OfRefItem item -> RefItemRepresentation.of(item.state());
            case Write.OfRefKind kind -> RefKindRepresentation.of(kind.state());
            case Write.OfAttributeDefinition definition -> AttributeDefinitionRepresentation.of(definition.state());
            case Write.OfHierarchy hierarchy -> HierarchyRepresentation.of(hierarchy.state());
            case Write.OfPerson person -> PersonRepresentation.of(person.state(),
                    ids.apply(ExternalId.Kind.PERSON), language);
            case Write.OfOrgUnit unit -> OrgUnitRepresentation.of(unit.state(),
                    ids.apply(ExternalId.Kind.ORG_UNIT), language);
            case Write.OfSuccession succession -> OrgUnitSuccessionsRepresentation.of(succession.state());
            case Write.OfTimeSettings time -> new TimeSettingsRepresentation(time.orgUnitId(), time.version(),
                    time.timeZone(), time.dayStart());
            case Write.OfOrganizationLink link -> new OrganizationLinkRepresentation(link.orgUnitId(),
                    link.version(), link.organizationId());
            case Write.OfEmployment employment -> EmploymentRepresentation.of(employment.state(),
                    ids.apply(ExternalId.Kind.EMPLOYMENT));
            case Write.OfAssignment assignment -> AssignmentRepresentation.of(assignment.state(),
                    ids.apply(ExternalId.Kind.ASSIGNMENT));
            case Write.OfReportingLine line -> ReportingLineRepresentation.of(line.state());
            case Write.OfUnitRole role -> UnitRoleRepresentation.of(role.state());
            case Write.OfQualification qualification -> QualificationRepresentation.of(qualification.state(),
                    ids.apply(ExternalId.Kind.QUALIFICATION));
            case Write.OfExternalId externalId -> ExternalIdRepresentation.of(externalId.state());
            case Write.OfChangeOrder order -> ChangeOrderRepresentation.of(order.state());
            case Write.Removed removed -> null;
        };
        Change change = write.deleted() ? Change.DELETE : write.created() ? Change.CREATE : Change.UPDATE;
        context.recordChange(change, write.resourceType(), write.resourceId(), write.path(), representation);
    }
}

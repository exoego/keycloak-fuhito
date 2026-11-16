package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
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
            record(context, new Write.OfPerson(resolved.created()));
        }
        return resolved.person().id();
    }

    static void record(RequestContext context, Write write) {
        Object representation = switch (write) {
            case Write.OfRefItem item -> RefItemRepresentation.of(item.state());
            case Write.OfRefKind kind -> RefKindRepresentation.of(kind.state());
            case Write.OfAttributeDefinition definition -> AttributeDefinitionRepresentation.of(definition.state());
            case Write.OfHierarchy hierarchy -> HierarchyRepresentation.of(hierarchy.state());
            case Write.OfPerson person -> PersonRepresentation.of(person.state());
            case Write.OfOrgUnit unit -> OrgUnitRepresentation.of(unit.state());
            case Write.OfSuccession succession -> OrgUnitSuccessionsRepresentation.of(succession.state());
            case Write.OfOrganizationLink link -> new OrganizationLinkRepresentation(link.orgUnitId(),
                    link.organizationId());
            case Write.OfEmployment employment -> EmploymentRepresentation.of(employment.state());
            case Write.OfAssignment assignment -> AssignmentRepresentation.of(assignment.state());
            case Write.OfReportingLine line -> ReportingLineRepresentation.of(line.state());
            case Write.OfUnitRole role -> UnitRoleRepresentation.of(role.state());
            case Write.OfQualification qualification -> QualificationRepresentation.of(qualification.state());
            case Write.OfExternalId externalId -> ExternalIdRepresentation.of(externalId.state());
            case Write.OfChangeOrder order -> ChangeOrderRepresentation.of(order.state());
            case Write.Removed removed -> null;
        };
        Change change = write.deleted() ? Change.DELETE : write.created() ? Change.CREATE : Change.UPDATE;
        context.recordChange(change, write.resourceType(), write.resourceId(), write.path(), representation);
    }
}

package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Every stored row of the data of one realm, with every period, in a form that a restore loads back.
 * Rows name Keycloak users by username, because the IDs of users differ between realms.
 *
 * @param format the name and the version of the format
 * @param refKinds the kinds of reference data that the realm added
 * @param refItems the reference items
 * @param attributeDefinitions the definitions of free attributes
 * @param hierarchies the hierarchies other than the default one
 * @param orgUnits the org units with their periods, parents, and successions
 * @param people the people
 * @param employments the employments with their terms
 * @param assignments the assignments with their periods
 * @param reportingLines the reporting lines
 * @param unitRoles the roles over org units
 * @param qualifications the qualifications
 * @param externalIds every ID that a system has for a resource, also the one of the system that made it
 * @param changeOrders the change orders
 * @param erasures the people whose data was erased
 */
@Schema(
        name = "RealmExport",
        description = "Every stored row of a realm, with every period. `POST /restore` loads it into a realm that "
                + "has no data of the extension, or in place of the data of a realm. Each resource has its "
                + "`version`, which the restore keeps. A resource with none starts at 1.")
public record RealmExport(
        @Schema(description = "The name and the version of the format.", examples = {"fuhito.export.v1"})
        String format,
        List<Kind> refKinds,
        List<Item> refItems,
        List<Definition> attributeDefinitions,
        List<HierarchyRow> hierarchies,
        List<Unit> orgUnits,
        List<PersonRow> people,
        List<EmploymentRow> employments,
        List<AssignmentRows> assignments,
        List<Line> reportingLines,
        List<Role> unitRoles,
        List<QualificationRow> qualifications,
        List<ExternalIdRow> externalIds,
        List<Order> changeOrders,
        List<Erasure> erasures) {

    /** The name and the version of the format that this extension writes and reads. */
    public static final String FORMAT = "fuhito.export.v1";

    /** The names of the lists, as the paths of the export name them. */
    public static final List<String> LISTS = List.of("ref-kinds", "ref-items", "attribute-definitions",
            "hierarchies", "org-units", "people", "employments", "assignments", "reporting-lines", "unit-roles",
            "qualifications", "external-ids", "change-orders", "erasures");

    @Schema(name = "ExportRefKind")
    public record Kind(String id, Long version, String code, String name, Map<String, String> names) {
    }

    @Schema(name = "ExportRefItem", description = "A reference item with every period of its names.")
    public record Item(String id, Long version, String kind, String code,
            @Schema(description = "Only in an export from before names had periods. A restore takes it as the "
                    + "name for every date when `versions` is missing.")
            String name,
            @Schema(description = "Only in an export from before names had periods, with `name`.")
            Map<String, String> names,
            int displayOrder, Map<String, Object> attributes, boolean active, boolean legalEntity,
            boolean actingHead, boolean lateral,
            @Schema(description = "The names of each period, oldest first.")
            List<ItemNames> versions) {
    }

    @Schema(name = "ExportRefItemNames", description = "The names of a reference item for a period. A missing "
            + "`validFrom` means that they hold from the start.")
    public record ItemNames(String id, String validFrom, String validTo, String name, Map<String, String> names) {
    }

    @Schema(name = "ExportAttributeDefinition")
    public record Definition(String id, Long version, String target, String name, String type, String refKind,
            boolean multiple, boolean required) {
    }

    @Schema(name = "ExportHierarchy")
    public record HierarchyRow(String id, Long version, String code, String name) {
    }

    @Schema(name = "ExportOrgUnit", description = "An org unit. It names its Keycloak organization by alias, "
            + "because the IDs of organizations differ between servers.")
    public record Unit(String id, Long version, String source, String timeZone, String dayStart,
            List<Version> versions, List<Parent> parents, List<Succession> successors,
            @Schema(description = "The alias of the Keycloak organization that the unit is linked to.")
            String organizationAlias) {
    }

    @Schema(name = "ExportOrgUnitVersion")
    public record Version(String id, String validFrom, String validTo, String code, String name,
            Map<String, String> names, String shortName, Map<String, String> shortNames, String phoneticName,
            String unitTypeId, String workLocationId, String costCenterId, int displayOrder,
            Map<String, Object> attributes) {
    }

    @Schema(name = "ExportOrgUnitParent", description = "A parent link. It names its hierarchy by code.")
    public record Parent(String id, String hierarchy, String parentId, String validFrom, String validTo) {
    }

    @Schema(name = "ExportOrgUnitSuccession")
    public record Succession(String id, String successorId, String effectiveDate) {
    }

    @Schema(name = "ExportErasure", description = "A person whose data was erased. A restore erases the person "
            + "again, also when an older export has the data.")
    public record Erasure(String personId, String erasedAt) {
    }

    @Schema(name = "ExportPerson", description = "A person. It names its Keycloak user by username.")
    public record PersonRow(String id, Long version, String username, String name, Map<String, String> names,
            String phoneticName, Map<String, Object> attributes, String source,
            @Schema(description = "The time of the mask, for a person who was masked.")
            String maskedAt) {
    }

    @Schema(name = "ExportEmployment")
    public record EmploymentRow(String id, Long version, String personId, String employerId, String validFrom,
            String validTo, String staffNumber, String endReasonId, String source, String continuesEmploymentId,
            List<Terms> terms) {
    }

    @Schema(name = "ExportEmploymentTerms")
    public record Terms(String id, String validFrom, String validTo, String employmentTypeId, String gradeId,
            String status, String leaveReasonId, BigDecimal workPercent, String contractEndDate,
            Map<String, Object> attributes) {
    }

    @Schema(name = "ExportAssignment", description = "An assignment with the rows of every period.")
    public record AssignmentRows(String assignmentId, Long version, List<Period> periods) {
    }

    @Schema(name = "ExportAssignmentPeriod")
    public record Period(String rowId, String employmentId, String orgUnitId, String validFrom, String validTo,
            String jobTitleId, String occupationId, String kindId, String workLocationId, String costCenterId,
            boolean head, boolean primary, BigDecimal allocationPercent, int displayOrder,
            Map<String, Object> attributes, String source, String continuesAssignmentId) {
    }

    @Schema(name = "ExportReportingLine")
    public record Line(String id, Long version, String assignmentId, String managerPersonId, String kind,
            String validFrom, String validTo) {
    }

    @Schema(name = "ExportUnitRole")
    public record Role(String id, Long version, String employmentId, String orgUnitId, String roleId,
            boolean includesDescendants, String validFrom, String validTo) {
    }

    @Schema(name = "ExportQualification")
    public record QualificationRow(String id, Long version, String personId, String kindId, String number,
            String validFrom, String validTo, Map<String, Object> attributes, String source) {
    }

    @Schema(name = "ExportExternalId", description = "The ID that one system has for a resource. A resource names "
            + "only the system that made it in `source`. Its IDs are all here.")
    public record ExternalIdRow(String id, Long version, String resourceType, String resourceId, String source,
            String externalId) {
    }

    @Schema(name = "ExportChangeOrder", description = "A change order. It names its users by username.")
    public record Order(String id, Long version, String number, String kind, String title, String note,
            String orderDate, String effectiveDate, String approver, String requester, String externalReference,
            String createdAt, String announcedAt, String announcedBy) {
    }
}

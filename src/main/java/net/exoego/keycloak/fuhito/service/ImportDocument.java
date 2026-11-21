package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ImportDocument",
        description = "The data of one import. The document describes the state from the effective date. A new "
                + "thing starts on that date, and a change to an existing thing takes effect on that date. A row "
                + "of a stored thing changes the values that it gives. A key that a row leaves out keeps the "
                + "stored value, and `null` clears it. An empty text, a text of only spaces, and an empty array "
                + "fail.",
        requiredProperties = {"effectiveDate"})
public record ImportDocument(
        @Schema(description = "The date of the state.", format = "date")
        String effectiveDate,
        @Schema(description = "True to do all checks and write nothing.", defaultValue = "false")
        boolean dryRun,
        @Schema(
                description = "The name of the system that the rows come from. A row can give its own `source`. A "
                        + "row that leaves it out, or gives `null`, takes this one. A row matches "
                        + "a stored record that has the ID of the row in that system, whichever system made the "
                        + "record. A new org unit, person, employment, assignment, or qualification keeps it as the "
                        + "system that made it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                defaultValue = "import")
        String source,
        List<RefItemRow> refItems,
        List<OrgUnitRow> orgUnits,
        List<EmploymentRow> employments,
        List<AssignmentRow> assignments,
        @Schema(description = "The people. They come before the employments, which can name them.")
        List<PersonRow> people,
        @Schema(description = "The kinds of reference data that the realm adds. They come first, so that the "
                + "reference items can name them.")
        List<RefKindRow> refKinds,
        @Schema(description = "The definitions of free attributes. They come after the kinds and before the "
                + "reference items, whose attributes they check.")
        List<AttributeDefinitionRow> attributeDefinitions,
        @Schema(description = "The hierarchies. They come before the org units.")
        List<HierarchyRow> hierarchies,
        @Schema(description = "The parents of org units in hierarchies. They come after the org units.")
        List<OrgUnitParentRow> orgUnitParents,
        @Schema(description = "The reporting lines. They come after the assignments.")
        List<ReportingLineRow> reportingLines,
        @Schema(description = "The roles over org units. They come after the employments.")
        List<UnitRoleRow> unitRoles,
        @Schema(description = "The qualifications. They come after the people.")
        List<QualificationRow> qualifications,
        @Schema(description = "The IDs of other systems. They come after every list of resources.")
        List<ExternalIdRow> externalIds,
        @Schema(description = "The change orders. They come first.")
        List<ChangeOrderRow> changeOrders) {

    /**
     * Makes a document with no list of kinds.
     *
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null
     * @param refItems the reference items, or null
     * @param orgUnits the org units, or null
     * @param employments the employments, or null
     * @param assignments the assignments, or null
     * @param people the people, or null
     */
    public ImportDocument(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
            List<OrgUnitRow> orgUnits, List<EmploymentRow> employments, List<AssignmentRow> assignments,
            List<PersonRow> people) {
        this(effectiveDate, dryRun, source, refItems, orgUnits, employments, assignments, people, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * Makes a document with no list of people.
     *
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null
     * @param refItems the reference items, or null
     * @param orgUnits the org units, or null
     * @param employments the employments, or null
     * @param assignments the assignments, or null
     */
    public ImportDocument(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
            List<OrgUnitRow> orgUnits, List<EmploymentRow> employments, List<AssignmentRow> assignments) {
        this(effectiveDate, dryRun, source, refItems, orgUnits, employments, assignments, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Schema(
            name = "ImportChangeOrderRow",
            description = "A change order. It is matched by its ID in the system of `source`, or by `id`. A row "
                    + "needs one of them. A row of a stored order changes the values that it gives. The "
                    + "announcement is not part of a row. A new order needs a title.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record ChangeOrderRow(
            @Schema(description = "The ID of a stored order. A CSV export has it in every row. A new order has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. An order keeps no source, so a row by `id` with this and no `externalId` "
                            + "fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the order in that system.")
            String externalId,
            @Schema(description = "A number that no other order has.", maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String number,
            @Schema(maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String kind,
            @Schema(maxLength = 255)
            String title,
            String note,
            @Schema(format = "date")
            String orderDate,
            @Schema(description = "A new order without it takes the effective date of the import. A stored order "
                    + "without it keeps its date, and one with `null` takes the effective date of the import.",
                    format = "date")
            String effectiveDate,
            @Schema(description = "The ID of the Keycloak user who approved it. Give this or `approverUsername`.")
            String approverUserId,
            @Schema(description = "The username of the user who approved it.")
            String approverUsername,
            @Schema(description = "The ID of the Keycloak user who asked for it. Give this or `requesterUsername`.")
            String requesterUserId,
            @Schema(description = "The username of the user who asked for it.")
            String requesterUsername,
            @Schema(description = "A reference in another system, such as a workflow.", maxLength = 255)
            String externalReference,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public ChangeOrderRow(String id, String source, String externalId, String number, String kind, String title,
                String note, String orderDate, String effectiveDate, String approverUserId, String approverUsername,
                String requesterUserId, String requesterUsername, String externalReference) {
            this(id, source, externalId, number, kind, title, note, orderDate, effectiveDate, approverUserId,
                    approverUsername, requesterUserId, requesterUsername, externalReference, Set.of());
        }
    }

    @Schema(
            name = "ImportExternalIdRow",
            description = "The ID that a system has for a resource. It is matched by type of resource, resource, "
                    + "and source. Another external ID replaces the stored one, and `null` removes it. The "
                    + "resource keeps the name of the system that made it.",
            requiredProperties = {"resourceType", "resourceId"})
    @JsonDeserialize(using = ImportRowReader.class)
    public record ExternalIdRow(
            @Schema(enumeration = {"ORG_UNIT", "PERSON", "EMPLOYMENT", "ASSIGNMENT", "QUALIFICATION", "REPORTING_LINE",
                    "UNIT_ROLE", "CHANGE_ORDER", "REF_ITEM", "REF_KIND", "HIERARCHY", "ATTRIBUTE_DEFINITION"})
            String resourceType,
            @Schema(description = "The ID of the resource.")
            String resourceId,
            @Schema(
                    description = "The name of the system. The default is the source of the document.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID in that system, or `null` to remove the stored one. A row that leaves "
                    + "it out keeps the stored one.")
            String externalId,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public ExternalIdRow(String resourceType, String resourceId, String source, String externalId) {
            this(resourceType, resourceId, source, externalId, Set.of());
        }
    }

    @Schema(
            name = "ImportQualificationRow",
            description = "A qualification of a person, such as a license. It is matched by its ID in the system of "
                    + "`source`, whichever system made it, or by `id`. A row needs one of them. A row of a stored "
                    + "one changes the kind, the number, the dates, and the attributes that it gives. A new one "
                    + "needs a kind.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record QualificationRow(
            @Schema(description = "The ID of a stored qualification. A CSV export has it in every row.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A row by `id` with this and neither `externalId` nor `personExternalId` must "
                            + "give the `source` that a read of the qualification has, or it fails with "
                            + "`import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the qualification in that system.")
            String externalId,
            @Schema(description = "The external ID of the person, with the same source. Give one of the three ways "
                    + "to name the person. A row of a stored qualification may name nobody.")
            String personExternalId,
            @Schema(description = "The ID of the person.")
            String personId,
            @Schema(description = "The username of the person. The person of the user is used, and created when the "
                    + "user has none.")
            String username,
            @Schema(description = "The code of a reference item of the kind `QUALIFICATION`.")
            String kind,
            @Schema(description = "The number, for example of a license.", maxLength = 255)
            String number,
            @Schema(description = "The first date. A new qualification starts on the effective date by default. A "
                    + "stored one keeps its start by default.", format = "date")
            String validFrom,
            @Schema(description = "The first date on which it no longer holds. `null` for no end.", format = "date")
            String validTo,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public QualificationRow(String id, String source, String externalId, String personExternalId, String personId,
                String username, String kind, String number, String validFrom, String validTo,
                Map<String, Object> attributes) {
            this(id, source, externalId, personExternalId, personId, username, kind, number, validFrom, validTo,
                    attributes, Set.of());
        }
    }

    @Schema(
            name = "ImportUnitRoleRow",
            description = "A role that an employment holds over an org unit. It is matched by its ID in the system "
                    + "of `source`, or by `id`. A row needs one of them. The employment, the org unit, the role, "
                    + "`includesDescendants`, and the start of a stored role do not change. A row with another value "
                    + "fails. A row that leaves one out keeps the stored value.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record UnitRoleRow(
            @Schema(description = "The ID of a stored role. A CSV export has it in every row. A new role has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row and its external IDs come from. The default is "
                            + "the source of the document. A role keeps no source, so a row by `id` with this and "
                            + "none of `externalId`, `employmentExternalId`, and `orgUnitExternalId` fails with "
                            + "`import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the role in that system. A different role needs a different ID.")
            String externalId,
            @Schema(description = "The external ID of the employment. Give this or `employmentId`.")
            String employmentExternalId,
            @Schema(description = "The ID of the employment.")
            String employmentId,
            @Schema(description = "The external ID of the org unit. Give this or `orgUnitId`.")
            String orgUnitExternalId,
            @Schema(description = "The ID of the org unit.")
            String orgUnitId,
            @Schema(description = "The code of a reference item of the kind `UNIT_ROLE`.")
            String role,
            @Schema(description = "True when the role also covers the units below.", defaultValue = "false")
            Boolean includesDescendants,
            @Schema(description = "The first date of a new role. The default is the effective date. A row of a "
                    + "stored role can leave it out or give the start of the role. Another date fails.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the role no longer holds. The default is the end of the "
                    + "employment.", format = "date")
            String validTo,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public UnitRoleRow(String id, String source, String externalId, String employmentExternalId,
                String employmentId, String orgUnitExternalId, String orgUnitId, String role,
                Boolean includesDescendants, String validFrom, String validTo) {
            this(id, source, externalId, employmentExternalId, employmentId, orgUnitExternalId, orgUnitId, role,
                    includesDescendants, validFrom, validTo, Set.of());
        }
    }

    @Schema(
            name = "ImportReportingLineRow",
            description = "A reporting line from an assignment to a manager. It is matched by its ID in the system "
                    + "of `source`, or by `id`. A row needs one of them. The assignment, the manager, the kind, and "
                    + "the start of a stored line do not change. A row with another value fails. A row that leaves "
                    + "one out keeps the stored value. A new solid line fails while the assignment has a solid line "
                    + "on the same dates.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record ReportingLineRow(
            @Schema(description = "The ID of a stored line. A CSV export has it in every row. A new line has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row and its external IDs come from. The default is "
                            + "the source of the document. A line keeps no source, so a row by `id` with this and none "
                            + "of `externalId`, `assignmentExternalId`, and `managerPersonExternalId` fails with "
                            + "`import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the line in that system. A different line needs a different ID.")
            String externalId,
            @Schema(description = "The external ID of the assignment of the person who reports. Give this or "
                    + "`assignmentId`.")
            String assignmentExternalId,
            @Schema(description = "The ID of the assignment.")
            String assignmentId,
            @Schema(description = "The external ID of the person who is the manager. Give one of the three ways "
                    + "to name the manager.")
            String managerPersonExternalId,
            @Schema(description = "The ID of the person who is the manager.")
            String managerPersonId,
            @Schema(description = "The username of the manager. The person of the user is used, and created when "
                    + "the user has none.")
            String managerUsername,
            @Schema(enumeration = {"SOLID", "DOTTED"}, defaultValue = "SOLID")
            String kind,
            @Schema(description = "The first date of a new line. The default is the effective date. A row of a "
                    + "stored line can leave it out or give the start of the line. Another date fails.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the line no longer holds. The default is the end of the "
                    + "assignment.", format = "date")
            String validTo,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public ReportingLineRow(String id, String source, String externalId, String assignmentExternalId,
                String assignmentId, String managerPersonExternalId, String managerPersonId, String managerUsername,
                String kind, String validFrom, String validTo) {
            this(id, source, externalId, assignmentExternalId, assignmentId, managerPersonExternalId, managerPersonId,
                    managerUsername, kind, validFrom, validTo, Set.of());
        }
    }

    @Schema(
            name = "ImportOrgUnitParentRow",
            description = "The parent of an org unit in a hierarchy from the effective date. It is matched by "
                    + "hierarchy and org unit. A row with `null` for the parent takes the unit out of the "
                    + "hierarchy, and a row that leaves out the parent keeps it.",
            requiredProperties = {"hierarchy"})
    @JsonDeserialize(using = ImportRowReader.class)
    public record OrgUnitParentRow(
            @Schema(description = "The code of the hierarchy.")
            String hierarchy,
            @Schema(
                    description = "The name of the system that the external IDs come from. The default is the source "
                            + "of the document. A parent keeps no source, so a row with this and neither "
                            + "`orgUnitExternalId` nor `parentExternalId` fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The external ID of the org unit. Give this or `orgUnitId`.")
            String orgUnitExternalId,
            @Schema(description = "The ID of the org unit.")
            String orgUnitId,
            @Schema(description = "The external ID of the parent. Give this or `parentId`, or `null` for no "
                    + "parent.")
            String parentExternalId,
            @Schema(description = "The ID of the parent.")
            String parentId,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public OrgUnitParentRow(String hierarchy, String source, String orgUnitExternalId, String orgUnitId,
                String parentExternalId, String parentId) {
            this(hierarchy, source, orgUnitExternalId, orgUnitId, parentExternalId, parentId, Set.of());
        }
    }

    @Schema(
            name = "ImportHierarchyRow",
            description = "A hierarchy. It is matched by its ID in the system of `source`, or by `id`. A row needs "
                    + "one of them. A row of a stored hierarchy changes its name. The code does not change. A new "
                    + "hierarchy needs a name.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record HierarchyRow(
            @Schema(description = "The ID of a stored hierarchy. A CSV export has it in every row. A new hierarchy "
                    + "has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A hierarchy keeps no source, so a row by `id` with this and no `externalId` "
                            + "fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the hierarchy in that system.")
            String externalId,
            @Schema(description = "A code that no other hierarchy has. A row of a stored hierarchy may leave it out.",
                    maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(maxLength = 255)
            String name,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public HierarchyRow(String id, String source, String externalId, String code, String name) {
            this(id, source, externalId, code, name, Set.of());
        }
    }

    @Schema(
            name = "ImportAttributeDefinitionRow",
            description = "The definition of a free attribute. It is matched by its ID in the system of `source`, "
                    + "or by `id`. A row needs one of them. A row of a stored definition changes the type, the kind, "
                    + "whether the value is a list, and whether a value is required. The target and the name do not "
                    + "change. A new definition needs a type.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record AttributeDefinitionRow(
            @Schema(description = "The ID of a stored definition. A CSV export has it in every row. A new "
                    + "definition has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A definition keeps no source, so a row by `id` with this and no `externalId` "
                            + "fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the definition in that system.")
            String externalId,
            @Schema(
                    description = "The kind of resource. A row of a stored definition may leave it out.",
                    enumeration = {"ORG_UNIT", "PERSON", "EMPLOYMENT_TERMS", "ASSIGNMENT", "QUALIFICATION",
                        "REF_ITEM"})
            String target,
            @Schema(description = "The name in `attributes`, which no other definition of the target has. A row of "
                    + "a stored definition may leave it out.", maxLength = 64)
            String name,
            @Schema(
                    description = "The type of the value. A `STRING` value must not be empty or only spaces, also "
                            + "in a list.",
                    enumeration = {"STRING", "NUMBER", "BOOLEAN", "DATE", "REF_ITEM"})
            String type,
            @Schema(description = "The kind of reference data. Only the type `REF_ITEM` has it.")
            String refKind,
            @Schema(description = "True when the value is a JSON array, and each element has the type. An empty "
                    + "array fails. Leave the name out or give null for no elements.",
                    defaultValue = "false")
            Boolean multiple,
            @Schema(description = "True when every write of the resource must give a value.", defaultValue = "false")
            Boolean required,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public AttributeDefinitionRow(String id, String source, String externalId, String target, String name,
                String type, String refKind, Boolean multiple, Boolean required) {
            this(id, source, externalId, target, name, type, refKind, multiple, required, Set.of());
        }
    }

    /**
     * Makes a document with one list.
     *
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null
     * @param list the name of the list, as the document names it, for example {@code orgUnits}
     * @param rows the rows of the list
     * @return the document
     */
    static ImportDocument of(String effectiveDate, boolean dryRun, String source, String list, List<?> rows) {
        Map<String, List<?>> lists = Map.of(list, rows);
        return new ImportDocument(effectiveDate, dryRun, source, rows(lists, "refItems"), rows(lists, "orgUnits"),
                rows(lists, "employments"), rows(lists, "assignments"), rows(lists, "people"),
                rows(lists, "refKinds"), rows(lists, "attributeDefinitions"), rows(lists, "hierarchies"),
                rows(lists, "orgUnitParents"), rows(lists, "reportingLines"), rows(lists, "unitRoles"),
                rows(lists, "qualifications"), rows(lists, "externalIds"), rows(lists, "changeOrders"));
    }

    // The name of the list tells the type of its rows.
    @SuppressWarnings("unchecked")
    private static <R> List<R> rows(Map<String, List<?>> lists, String list) {
        return (List<R>) lists.get(list);
    }

    @Schema(
            name = "ImportRefKindRow",
            description = "A kind of reference data that the realm adds. It is matched by its ID in the system of "
                    + "`source`, or by `id`. A row needs one of them. A row of a stored kind changes its names. The "
                    + "code does not change. A new kind needs `primaryLanguage` and `names`.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record RefKindRow(
            @Schema(description = "The ID of a stored kind. A CSV export has it in every row. A new kind has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A kind keeps no source, so a row by `id` with this and no `externalId` fails "
                            + "with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the kind in that system.")
            String externalId,
            @Schema(description = "A code that no other kind has. A row of a stored kind may leave it out.",
                    maxLength = 32, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(description = "The language tag of the primary name, such as `ja`. It matches a key of `names` "
                    + "in standard form.")
            String primaryLanguage,
            @Schema(description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. They merge with the "
                    + "stored names by language, and `null` for a language removes its names. Two keys of one tag in "
                    + "standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
            Map<String, RefKindName> names,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public RefKindRow(String id, String source, String externalId, String code, String primaryLanguage,
                Map<String, RefKindName> names) {
            this(id, source, externalId, code, primaryLanguage, names, Set.of());
        }
    }

    @Schema(
            name = "ImportPersonRow",
            description = "A person, also one with no Keycloak user. It is matched by its ID in the system of "
                    + "`source`, whichever system made it, or by `id`. A row needs one of them.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record PersonRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A row by `id` with this and no `externalId` must give the `source` that a "
                            + "read of the person has, or it fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the person in that system.")
            String externalId,
            @Schema(description = "The username of a Keycloak user to link. A row that leaves out the user keeps the "
                    + "link that is stored, and `null` for both ways removes it.")
            String username,
            @Schema(description = "The ID of a Keycloak user to link. Give this or `username`. A row with both fails "
                    + "with `import.user_twice`.")
            String userId,
            @Schema(description = "The language tag of the primary names, such as `ja`. It matches a key of `names` "
                    + "in standard form.")
            String primaryLanguage,
            @Schema(description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. A person with no "
                    + "user needs them. They merge with the stored names by language and by field. Two keys of one "
                    + "tag in standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
            Map<String, PersonName> names,
            @Schema(description = "Free values of the person.")
            Map<String, Object> attributes,
            @Schema(description = "The ID of a stored person, for a row of a person with no external ID. A CSV export "
                    + "has it in every row. A new person has none.")
            String id,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public PersonRow(String source, String externalId, String username, String userId, String primaryLanguage,
                Map<String, PersonName> names, Map<String, Object> attributes, String id) {
            this(source, externalId, username, userId, primaryLanguage, names, attributes, id, Set.of());
        }
    }

    @Schema(
            name = "ImportEmploymentRow",
            description = "An employment. It is matched by its ID in the system of `source`, whichever system made "
                    + "it, or by `id`. A row needs one of them.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record EmploymentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A row by `id` with this and none of `externalId`, `personExternalId`, "
                            + "`employerExternalId`, and `continuesEmploymentExternalId` must give the `source` that "
                            + "a read of the employment has, or it fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the employment in that system.")
            String externalId,
            @Schema(description = "The username of the Keycloak user. A row needs this, `userId`, or "
                    + "`personExternalId`, except for a stored employment.")
            String username,
            @Schema(description = "The ID of the Keycloak user. Give this or `username`. A row with both fails with "
                    + "`import.user_twice`.")
            String userId,
            @Schema(
                    description = "The number that the legal entity gives to the person. It is used only when the "
                            + "employment is created.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String staffNumber,
            @Schema(description = "The code of the employment type.")
            String employmentType,
            @Schema(description = "The code of the grade.")
            String grade,
            @Schema(ref = "EmploymentStatus", defaultValue = "ACTIVE")
            String status,
            @Schema(description = "The code of the leave reason. Only the status `ON_LEAVE` may have one.")
            String leaveReason,
            @Schema(
                    description = "The share of full-time work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal workPercent,
            @Schema(
                    description = "The last day of the fixed-term contract. `null` for an indefinite contract.",
                    format = "date")
            String contractEndDate,
            @Schema(description = "Free values of the terms.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new employment. The default is the effective date. A row "
                            + "of a stored employment can leave it out or give the start of the employment, not of "
                            + "its terms. Another date fails.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the employment no longer holds. `null` removes the end of "
                    + "a stored employment.", format = "date")
            String validTo,
            @Schema(description = "The code of the end reason. It is used only with an end.")
            String endReason,
            @Schema(description = "The external ID of the org unit that employs the person. It is a legal entity. "
                    + "Leave it out when the realm has exactly one, or for an external employer. A stored employment "
                    + "keeps its employer, so the value must name that one.")
            String employerExternalId,
            @Schema(description = "The external ID of the person with the same source, for a person from the list "
                    + "of people. It names a person with no Keycloak user too.")
            String personExternalId,
            @Schema(description = "The ID of the person, for a person with no external ID of the same source. Give "
                    + "this or `personExternalId`, not both.")
            String personId,
            @Schema(description = "The ID of the employer, for an employer with no external ID of the same source. "
                    + "Give this or `employerExternalId`, not both.")
            String employerId,
            @Schema(description = "The ID of a stored employment, for a row of an employment with no external ID. A "
                    + "CSV export has it in every row. A new employment has none.")
            String id,
            @Schema(description = "The external ID of the employment that this one continues after a transfer "
                    + "between employers, with the same source. A new employment gets the link. A stored one must "
                    + "have the same. The one it continues must end by the start of this one.")
            String continuesEmploymentExternalId,
            @Schema(description = "The ID of the employment that this one continues. Give this or "
                    + "`continuesEmploymentExternalId`, not both.")
            String continuesEmploymentId,
            @Schema(description = "The code of the employer outside the realm, a reference item of the kind "
                    + "`EXTERNAL_EMPLOYER`, such as a staffing agency. Give this or the employer, not both. A new "
                    + "employment needs an active item. A stored employment keeps its employer, so the value must "
                    + "name that one.")
            String externalEmployer,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
                String contractEndDate, Map<String, Object> attributes, String validFrom, String validTo,
                String endReason, String employerExternalId, String personExternalId, String personId,
                String employerId, String id, String continuesEmploymentExternalId, String continuesEmploymentId,
                String externalEmployer) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, leaveReason,
                    workPercent, contractEndDate, attributes, validFrom, validTo, endReason, employerExternalId,
                    personExternalId, personId, employerId, id, continuesEmploymentExternalId, continuesEmploymentId,
                    externalEmployer, Set.of());
        }

        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
                String contractEndDate, Map<String, Object> attributes, String validFrom, String validTo,
                String endReason, String employerExternalId, String personExternalId, String personId,
                String employerId, String id, String continuesEmploymentExternalId, String continuesEmploymentId) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, leaveReason,
                    workPercent, contractEndDate, attributes, validFrom, validTo, endReason, employerExternalId,
                    personExternalId, personId, employerId, id, continuesEmploymentExternalId, continuesEmploymentId,
                    null);
        }

        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
                Map<String, Object> attributes, String validFrom, String validTo, String endReason,
                String employerExternalId, String personExternalId, String personId, String employerId, String id,
                String continuesEmploymentExternalId, String continuesEmploymentId) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, leaveReason,
                    workPercent, null, attributes, validFrom, validTo, endReason, employerExternalId,
                    personExternalId, personId, employerId, id, continuesEmploymentExternalId, continuesEmploymentId);
        }

        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
                Map<String, Object> attributes, String validFrom, String validTo, String endReason,
                String employerExternalId, String personExternalId, String personId, String employerId, String id) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, leaveReason,
                    workPercent, null, attributes, validFrom, validTo, endReason, employerExternalId,
                    personExternalId, personId, employerId, id, null, null);
        }

        /**
         * Makes a row that names no employer.
         *
         * @param source the name of the system that the row comes from, or null
         * @param externalId the ID of the employment in that system
         * @param username the username of the user, or null
         * @param userId the ID of the user, or null
         * @param staffNumber the staff number, or null
         * @param employmentType the code of the employment type, or null
         * @param grade the code of the grade, or null
         * @param status the status, or null
         * @param workPercent the share of full-time work in percent, or null
         * @param attributes free values, or null
         * @param validFrom the first date of the employment, or null
         * @param validTo the first date on which the employment no longer holds, or null
         * @param endReason the code of the end reason, or null
         */
        public EmploymentRow(String source, String externalId, String username, String userId, String staffNumber,
                String employmentType, String grade, String status, BigDecimal workPercent,
                Map<String, Object> attributes, String validFrom, String validTo, String endReason) {
            this(source, externalId, username, userId, staffNumber, employmentType, grade, status, null,
                    workPercent, null, attributes, validFrom, validTo, endReason, null, null, null, null, null, null,
                    null);
        }
    }

    @Schema(
            name = "ImportAssignmentRow",
            description = "An assignment. It is matched by its ID in the system of `source`, whichever system made "
                    + "it, or by `id`. A row needs one of them. A new assignment names its employment and its org "
                    + "unit.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record AssignmentRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A row by `id` with this and none of `externalId`, `employmentExternalId`, "
                            + "`orgUnitExternalId`, and `continuesAssignmentExternalId` must give the `source` that a "
                            + "read of the assignment has, or it fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the assignment in that system.")
            String externalId,
            @Schema(description = "The external ID of the employment with the same source. A row of a stored "
                    + "assignment that leaves out the employment keeps the one that is stored.")
            String employmentExternalId,
            @Schema(description = "The external ID of the org unit with the same source. A row of a stored "
                    + "assignment that leaves out the org unit keeps the one that is stored.")
            String orgUnitExternalId,
            @Schema(description = "The code of the job title.")
            String jobTitle,
            @Schema(description = "The code of the occupation.")
            String occupation,
            @Schema(description = "The code of the assignment kind.")
            String kind,
            @Schema(description = "True when the person leads the org unit.", defaultValue = "false")
            Boolean head,
            @Schema(description = "True when this is the main assignment of the employment.", defaultValue = "false")
            Boolean primary,
            @Schema(
                    description = "The share of the person's work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal allocationPercent,
            @Schema(description = "The position among the members of the org unit.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new assignment. The default is the effective date. A row "
                            + "of a stored assignment can leave it out or give the start of the whole assignment, "
                            + "not of its details. Another date fails.",
                    format = "date")
            String validFrom,
            @Schema(
                    description = "The first date on which the assignment no longer holds. The default is the end "
                            + "of the employment. `null` gives a stored assignment the end of its employment.",
                    format = "date")
            String validTo,
            @Schema(description = "The ID of the employment, for an employment with no external ID of the same "
                    + "source. Give this or `employmentExternalId`, not both.")
            String employmentId,
            @Schema(description = "The ID of the org unit, for an org unit with no external ID of the same source. "
                    + "Give this or `orgUnitExternalId`, not both.")
            String orgUnitId,
            @Schema(description = "The ID of a stored assignment, for a row of an assignment with no external ID. A "
                    + "CSV export has it in every row. A new assignment has none.")
            String id,
            @Schema(description = "The external ID of the assignment that this one continues after a transfer "
                    + "between employers, with the same source. A new assignment gets the link. A stored one must "
                    + "have the same. The one it continues must end by the start of this one.")
            String continuesAssignmentExternalId,
            @Schema(description = "The ID of the assignment that this one continues. Give this or "
                    + "`continuesAssignmentExternalId`, not both.")
            String continuesAssignmentId,
            @Schema(description = "The code of the work location. With none, the one of the org unit applies.")
            String workLocation,
            @Schema(description = "The code of the cost center. With none, the one of the org unit applies.")
            String costCenter,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public AssignmentRow(String source, String externalId, String employmentExternalId, String orgUnitExternalId,
                String jobTitle, String occupation, String kind, Boolean head, Boolean primary,
                BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes, String validFrom,
                String validTo, String employmentId, String orgUnitId, String id, String continuesAssignmentExternalId,
                String continuesAssignmentId, String workLocation, String costCenter) {
            this(source, externalId, employmentExternalId, orgUnitExternalId, jobTitle, occupation, kind, head, primary,
                    allocationPercent, displayOrder, attributes, validFrom, validTo, employmentId, orgUnitId, id,
                    continuesAssignmentExternalId, continuesAssignmentId, workLocation, costCenter, Set.of());
        }

        public AssignmentRow(String source, String externalId, String employmentExternalId,
                String orgUnitExternalId, String jobTitle, String occupation, String kind, Boolean head,
                Boolean primary, BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes,
                String validFrom, String validTo, String employmentId, String orgUnitId, String id) {
            this(source, externalId, employmentExternalId, orgUnitExternalId, jobTitle, occupation, kind, head,
                    primary, allocationPercent, displayOrder, attributes, validFrom, validTo, employmentId, orgUnitId,
                    id, null, null, null, null);
        }

        /**
         * Makes a row that names the employment and the org unit by external ID.
         *
         * @param source the name of the system that the row comes from, or null
         * @param externalId the ID of the assignment in that system
         * @param employmentExternalId the external ID of the employment
         * @param orgUnitExternalId the external ID of the org unit
         * @param jobTitle the code of the job title, or null
         * @param occupation the code of the occupation, or null
         * @param kind the code of the assignment kind, or null
         * @param head true when the person leads the org unit, or null
         * @param primary true when this is the main assignment of the employment, or null
         * @param allocationPercent the share of the person's work in percent, or null
         * @param displayOrder the position among the members of the org unit, or null
         * @param attributes free values, or null
         * @param validFrom the first date of the assignment, or null
         * @param validTo the first date on which the assignment no longer holds, or null
         */
        public AssignmentRow(String source, String externalId, String employmentExternalId,
                String orgUnitExternalId, String jobTitle, String occupation, String kind, Boolean head,
                Boolean primary, BigDecimal allocationPercent, Integer displayOrder, Map<String, Object> attributes,
                String validFrom, String validTo) {
            this(source, externalId, employmentExternalId, orgUnitExternalId, jobTitle, occupation, kind, head,
                    primary, allocationPercent, displayOrder, attributes, validFrom, validTo, null, null, null, null,
                    null, null, null);
        }
    }

    @Schema(
            name = "ImportRefItemRow",
            description = "A reference item. It is matched by its ID in the system of `source`, or by `id`. A row "
                    + "needs one of them. The kind and the code of a stored item do not change. A new item needs "
                    + "`primaryLanguage` and `names`.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record RefItemRow(
            @Schema(description = "The ID of a stored item. A CSV export has it in every row. A new item has none.")
            String id,
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. An item keeps no source, so a row by `id` with this and no `externalId` "
                            + "fails with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the item in that system.")
            String externalId,
            @Schema(description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added. A "
                    + "row of a stored item may leave it out.")
            String kind,
            @Schema(description = "A code that no other item of the kind has. A row of a stored item may leave it "
                    + "out.", maxLength = 64, pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(description = "The language tag of the primary name, such as `ja`. It matches a key of `names` "
                    + "in standard form.")
            String primaryLanguage,
            @Schema(description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. They merge with the "
                    + "stored names of the date by language, and `null` for a language removes its names. Two keys "
                    + "of one tag in standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
            Map<String, RefItemName> names,
            @Schema(description = "The position in lists.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(description = "False to stop the use of the item for new data.", defaultValue = "true")
            Boolean active,
            @Schema(description = "True for a unit type whose org units are legal entities. A row that leaves it "
                    + "out keeps the mark of a stored item.")
            Boolean legalEntity,
            @Schema(description = "True for an assignment kind whose heads stand in for an absent head. A row "
                    + "that leaves it out keeps the mark of a stored item.")
            Boolean actingHead,
            @Schema(description = "True for a unit type whose org units are lateral units, outside the line "
                    + "organization. A row that leaves it out keeps the mark of a stored item.")
            Boolean lateral,
            @Schema(description = "True for a unit type whose org units are rollup units, which gather units in "
                    + "another hierarchy and are not in the default hierarchy. A row that leaves it out keeps the "
                    + "mark of a stored item.")
            Boolean rollup,
            @Schema(
                    description = "The first date of the names of the row. A new item without it has names that "
                            + "hold for every date. A stored item without it gets the names from the effective "
                            + "date. A date that starts stored names corrects them.",
                    format = "date")
            String validFrom,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public RefItemRow(String id, String source, String externalId, String kind, String code, String primaryLanguage,
                Map<String, RefItemName> names, Integer displayOrder, Map<String, Object> attributes, Boolean active,
                Boolean legalEntity, Boolean actingHead, Boolean lateral, Boolean rollup, String validFrom) {
            this(id, source, externalId, kind, code, primaryLanguage, names, displayOrder, attributes, active,
                    legalEntity, actingHead, lateral, rollup, validFrom, Set.of());
        }

        /**
         * Makes a row of the source of the document that does not speak about the marks, with names from the
         * effective date.
         *
         * @param externalId the ID of the item in the source of the document
         * @param kind the kind
         * @param code the code
         * @param primaryLanguage the tag of the language of the primary name
         * @param names the names by language tag
         * @param displayOrder the position in lists, or null
         * @param attributes free values, or null
         * @param active false to stop the use of the item for new data, or null
         */
        public RefItemRow(String externalId, String kind, String code, String primaryLanguage,
                Map<String, RefItemName> names, Integer displayOrder, Map<String, Object> attributes, Boolean active) {
            this(null, null, externalId, kind, code, primaryLanguage, names, displayOrder, attributes, active, null,
                    null, null, null, null);
        }
    }

    @Schema(
            name = "ImportOrgUnitRow",
            description = "An org unit. It is matched by its ID in the system of `source`, whichever system made "
                    + "it, or by `id`. A row needs one of them. A new unit needs `primaryLanguage` and `names`.")
    @JsonDeserialize(using = ImportRowReader.class)
    public record OrgUnitRow(
            @Schema(
                    description = "The name of the system that the row comes from. The default is the source of the "
                            + "document. A row by `id` with this and none of `externalId`, `parentExternalId`, and "
                            + "`successorExternalIds` must give the `source` that a read of the unit has, or it fails "
                            + "with `import.value_fixed`.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String source,
            @Schema(description = "The ID of the unit in that system.")
            String externalId,
            @Schema(
                    description = "A code that no other org unit has on the same date. A row for a stored unit "
                            + "changes it from the effective date.",
                    maxLength = 64,
                    pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
            String code,
            @Schema(description = "The language tag of the primary names, such as `ja`. It matches a key of `names` "
                    + "in standard form.")
            String primaryLanguage,
            @Schema(description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. They merge with the "
                    + "stored names by language and by field, and `null` for a language removes its names. Two keys "
                    + "of one tag in standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
            Map<String, OrgUnitName> names,
            @Schema(description = "The code of the unit type.")
            String unitType,
            @Schema(description = "The external ID of the parent in `hierarchy`, with the same source. A root has "
                    + "none. A row of a stored unit that leaves out the parent keeps it, and `null` takes the parent "
                    + "away.")
            String parentExternalId,
            @Schema(description = "The ID of the parent, for a parent that has no external ID with the same source. "
                    + "Give this or `parentExternalId`, not both.")
            String parentId,
            @Schema(description = "The position among siblings.", defaultValue = "0")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes,
            @Schema(
                    description = "The first date of a new unit. The default is the effective date. A row of a "
                            + "stored unit can leave it out or give the first date of the unit, not of its details. "
                            + "Another date fails.",
                    format = "date")
            String validFrom,
            @Schema(description = "The first date on which the unit no longer exists. `null` removes the end of a "
                    + "stored unit.", format = "date")
            String validTo,
            @Schema(description = "The ID of a stored unit, for a row of a unit with no external ID. A CSV export has "
                    + "it in every row. A new unit has none.")
            String id,
            @Schema(description = "The external IDs of the units that follow this one from `validTo`, with the same "
                    + "source. A row with successors needs `validTo`. The end and the successors are one write.")
            List<String> successorExternalIds,
            @Schema(description = "The IDs of the units that follow this one from `validTo`.")
            List<String> successorIds,
            @Schema(description = "The alias of the Keycloak organization that the unit is linked to. A row that "
                    + "leaves it out keeps the link. `null` removes it.")
            String organizationAlias,
            @Schema(description = "The time zone of the unit and the units below, for example `Asia/Tokyo`. A row "
                    + "that leaves it out keeps it. `null` removes it.")
            String timeZone,
            @Schema(description = "The time of day when the date changes, as `HH:mm`. A row that leaves it out keeps "
                    + "it. `null` removes it.")
            String dayStart,
            @Schema(description = "The code of the work location that the unit gives its members.")
            String workLocation,
            @Schema(description = "The code of the cost center that the unit gives its members.")
            String costCenter,
            @Schema(
                    description = "The code of the hierarchy of the parent, as a read of an org unit has it. A row "
                            + "that leaves it out gives the parent in the default hierarchy. A row of a stored unit "
                            + "gives the parent in this hierarchy from the effective date. A code that no hierarchy has "
                            + "fails with `hierarchy.unknown`. A row of a new unit takes only `default`, or none, as "
                            + "`POST /org-units` does. Another value fails with `invalid.value_fixed`. A CSV file has no "
                            + "such column.")
            String hierarchy,
            @Schema(hidden = true)
            Set<String> nulls) implements ImportRow {

        public OrgUnitRow(String source, String externalId, String code, String primaryLanguage,
                Map<String, OrgUnitName> names, String unitType, String parentExternalId, String parentId,
                Integer displayOrder, Map<String, Object> attributes, String validFrom, String validTo, String id,
                List<String> successorExternalIds, List<String> successorIds, String organizationAlias, String timeZone,
                String dayStart, String workLocation, String costCenter) {
            this(source, externalId, code, primaryLanguage, names, unitType, parentExternalId, parentId, displayOrder,
                    attributes, validFrom, validTo, id, successorExternalIds, successorIds, organizationAlias, timeZone,
                    dayStart, workLocation, costCenter, null, Set.of());
        }

        public OrgUnitRow(String source, String externalId, String code, String primaryLanguage,
                Map<String, OrgUnitName> names, String unitType, String parentExternalId, String parentId,
                Integer displayOrder, Map<String, Object> attributes, String validFrom, String validTo, String id) {
            this(source, externalId, code, primaryLanguage, names, unitType, parentExternalId, parentId,
                    displayOrder, attributes, validFrom, validTo, id, null, null, null, null, null, null, null);
        }
    }
}

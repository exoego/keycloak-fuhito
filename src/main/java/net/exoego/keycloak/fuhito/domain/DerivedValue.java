package net.exoego.keycloak.fuhito.domain;

/**
 * A value of a read that comes from other resources or from Keycloak. A change of it changes the read of a resource
 * that nobody wrote. So the write that changes it also counts up the version of that resource, in the same
 * transaction, and adds a propagated change of it to the change log.
 *
 * <p>This is the list of every such value. A value that is missing here changes a read with no entry.
 */
public enum DerivedValue {

    /** The Keycloak organization that an org unit links. */
    KEYCLOAK_ORGANIZATION_ID(ResourceType.FUHITO_ORG_UNIT, "org-units", "keycloakOrganizationId",
            "The organizations of Keycloak", "The removal of the organization in Keycloak"),

    /** The Keycloak user of the person of an employment. */
    EMPLOYMENT_USER_ID(ResourceType.FUHITO_EMPLOYMENT, "employments", "userId",
            "The user that the person is linked to", "A link or an unlink of the user of the person"),

    /** The Keycloak user of the person of an assignment. */
    ASSIGNMENT_USER_ID(ResourceType.FUHITO_ASSIGNMENT, "assignments", "userId",
            "The user that the person is linked to", "A link or an unlink of the user of the person"),

    /** The Keycloak user of the person of a qualification. */
    QUALIFICATION_USER_ID(ResourceType.FUHITO_QUALIFICATION, "qualifications", "userId",
            "The user that the person is linked to", "A link or an unlink of the user of the person"),

    /** The Keycloak user of the person of a role over an org unit. */
    UNIT_ROLE_USER_ID(ResourceType.FUHITO_UNIT_ROLE, "unit-roles", "userId",
            "The user that the person is linked to", "A link or an unlink of the user of the person"),

    /** The Keycloak user of the manager of a reporting line. */
    REPORTING_LINE_MANAGER_USER_ID(ResourceType.FUHITO_REPORTING_LINE, "reporting-lines", "managerUserId",
            "The user that the manager is linked to", "A link or an unlink of the user of the manager"),

    /** The name of a person with no names in the record, which is the name of the linked Keycloak user. */
    PERSON_NAME(ResourceType.FUHITO_PERSON, "people", "name",
            "The first name and the last name of the linked Keycloak user, in the order of the realm attribute "
                    + "fuhito.nameOrder",
            "A change of the name of the user in Keycloak, when Keycloak sends an event of it, the removal of the "
                    + "user, and a change of the realm attribute fuhito.nameOrder");

    private final ResourceType resourceType;
    private final String collection;
    private final String field;
    private final String readFrom;
    private final String changedBy;

    DerivedValue(ResourceType resourceType, String collection, String field, String readFrom, String changedBy) {
        this.resourceType = resourceType;
        this.collection = collection;
        this.field = field;
        this.readFrom = readFrom;
        this.changedBy = changedBy;
    }

    /**
     * Returns the type of the resource whose read has the value.
     *
     * @return the type
     */
    public ResourceType resourceType() {
        return resourceType;
    }

    /**
     * Returns the path of a resource whose read has the value.
     *
     * @param resourceId the ID of the resource
     * @return the path below the realm, for example {@code fuhito/org-units/123}
     */
    public String path(String resourceId) {
        return "fuhito/" + collection + "/" + resourceId;
    }

    /**
     * Returns the name of the field of the read.
     *
     * @return the name, as the read has it
     */
    public String field() {
        return field;
    }

    /**
     * Says what the value is read from.
     *
     * @return the source, for people
     */
    public String readFrom() {
        return readFrom;
    }

    /**
     * Says which writes change the value.
     *
     * @return the writes, for people
     */
    public String changedBy() {
        return changedBy;
    }
}

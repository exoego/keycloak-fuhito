package net.exoego.keycloak.fuhito.domain;

/**
 * Who employs the person of an employment. It is a legal entity of the realm, or an employer outside the realm, such
 * as a staffing agency or a contractor. Exactly one of the two IDs is set.
 *
 * @param legalEntityId the ID of the org unit that is a legal entity, or null for an external employer
 * @param externalEmployerId the ID of the reference item of the kind {@link RefKind#EXTERNAL_EMPLOYER}, or null for a
 *        legal entity
 */
public record Employer(String legalEntityId, String externalEmployerId) {

    /**
     * Makes an employer.
     *
     * @param legalEntityId the ID of the org unit that is a legal entity, or null for an external employer
     * @param externalEmployerId the ID of the reference item of the external employer, or null for a legal entity
     * @throws IllegalArgumentException when both IDs or neither are set
     */
    public Employer {
        if ((legalEntityId == null) == (externalEmployerId == null)) {
            throw new IllegalArgumentException("An employer is either a legal entity or an external employer");
        }
    }

    /**
     * Makes an employer that is a legal entity of the realm.
     *
     * @param orgUnitId the ID of the org unit
     * @return the employer
     */
    public static Employer legalEntity(String orgUnitId) {
        return new Employer(orgUnitId, null);
    }

    /**
     * Makes an employer outside the realm.
     *
     * @param refItemId the ID of the reference item of the kind {@link RefKind#EXTERNAL_EMPLOYER}
     * @return the employer
     */
    public static Employer external(String refItemId) {
        return new Employer(null, refItemId);
    }

    /**
     * Tells whether the employer is outside the realm.
     *
     * @return true for an external employer
     */
    public boolean isExternal() {
        return externalEmployerId != null;
    }
}

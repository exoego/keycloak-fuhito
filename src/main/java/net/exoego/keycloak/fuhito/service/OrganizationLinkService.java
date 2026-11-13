package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;

/**
 * Links org units to Keycloak organizations.
 * The link says that an org unit, for example a hospital, stands for a Keycloak organization.
 */
public class OrganizationLinkService {

    private final OrgUnitStore orgUnits;
    private final OrganizationDirectory organizations;

    /**
     * Creates the service.
     *
     * @param orgUnits the storage of org units of the realm
     * @param organizations the Keycloak organizations of the realm
     */
    public OrganizationLinkService(OrgUnitStore orgUnits, OrganizationDirectory organizations) {
        this.orgUnits = orgUnits;
        this.organizations = organizations;
    }

    /**
     * Links an org unit to a Keycloak organization. A link that the unit has already is replaced.
     *
     * @param orgUnitId the ID of the org unit
     * @param organizationId the ID of the Keycloak organization
     * @return the org unit with the link
     * @throws FuhitoException when the unit or the organization does not exist, or another unit has the
     *         organization
     */
    public OrgUnit link(String orgUnitId, String organizationId) {
        Checks.required("organizationId", organizationId);
        OrgUnit unit = require(orgUnitId);
        if (!organizations.exists(organizationId)) {
            throw FuhitoException.invalid("organization.unknown", Map.of("organizationId", organizationId));
        }
        orgUnits.findUnitByOrganizationId(organizationId)
                .filter(other -> !other.id().equals(orgUnitId))
                .ifPresent(other -> {
                    throw FuhitoException.conflict("organization.linked",
                            Map.of("organizationId", organizationId, "orgUnitId", other.id()));
                });
        return store(unit, organizationId);
    }

    /**
     * Removes the link of an org unit to a Keycloak organization. A unit with no link stays as it is.
     *
     * @param orgUnitId the ID of the org unit
     * @return the org unit with no link
     * @throws FuhitoException when the unit does not exist
     */
    public OrgUnit unlink(String orgUnitId) {
        return store(require(orgUnitId), null);
    }

    private OrgUnit require(String orgUnitId) {
        return orgUnits.findUnit(orgUnitId)
                .orElseThrow(() -> FuhitoException.notFound("org_unit.not_found", Map.of("id", orgUnitId)));
    }

    private OrgUnit store(OrgUnit unit, String organizationId) {
        OrgUnit changed = new OrgUnit(unit.id(), unit.code(), unit.externalId(), unit.source(), organizationId);
        orgUnits.updateUnit(changed);
        return changed;
    }
}

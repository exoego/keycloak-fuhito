package net.exoego.keycloak.fuhito.service;

import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationLinkServiceTest {

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final OrganizationLinkService service =
            new OrganizationLinkService(store, Set.of("org-x", "org-y")::contains);

    @BeforeEach
    void createUnits() {
        store.insertUnit(new OrgUnit("x", "x-hospital", "H-1", "hr", null));
        store.insertUnit(new OrgUnit("y", null, null, null, null));
    }

    @Test
    void linksReplacesAndRemovesALink() {
        OrgUnit linked = service.link("x", "org-x");

        assertEquals("org-x", linked.keycloakOrganizationId());
        assertEquals("x-hospital", linked.code());
        assertEquals("H-1", linked.externalId());
        assertEquals("hr", linked.source());
        assertEquals(linked, store.findUnit("x").orElseThrow());
        // The same link again is fine, and a unit can change its organization.
        assertEquals("org-x", service.link("x", "org-x").keycloakOrganizationId());
        assertEquals("org-y", service.link("x", "org-y").keycloakOrganizationId());
        assertEquals("org-x", service.link("y", "org-x").keycloakOrganizationId());

        assertNull(service.unlink("x").keycloakOrganizationId());
        assertNull(service.unlink("x").keycloakOrganizationId());
        assertEquals("org-y", service.link("y", "org-y").keycloakOrganizationId());
    }

    @Test
    void oneOrganizationHasOneOrgUnit() {
        service.link("x", "org-x");

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.link("y", "org-x"));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("organization.linked", e.code());
        assertEquals(Map.of("organizationId", "org-x", "orgUnitId", "x"), e.params());
        assertNull(store.findUnit("y").orElseThrow().keycloakOrganizationId());
    }

    @Test
    void rejectsUnknownThings() {
        FuhitoException organization = assertThrows(FuhitoException.class, () -> service.link("x", "nope"));
        assertEquals(FuhitoException.Reason.INVALID, organization.reason());
        assertEquals("organization.unknown", organization.code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.link("x", null)).code());
        FuhitoException unit = assertThrows(FuhitoException.class, () -> service.link("nope", "org-x"));
        assertEquals(FuhitoException.Reason.NOT_FOUND, unit.reason());
        assertEquals("org_unit.not_found", unit.code());
        assertEquals("org_unit.not_found", assertThrows(FuhitoException.class, () -> service.unlink("nope")).code());
    }
}

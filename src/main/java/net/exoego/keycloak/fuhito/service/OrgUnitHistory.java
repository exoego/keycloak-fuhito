package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;

/**
 * Everything that is stored about an org unit over time.
 *
 * @param unit the identity of the unit
 * @param versions the details of each period, oldest first. The parent of each entry is not set.
 * @param parents the parents in every hierarchy, oldest first. A gap in a hierarchy means the unit had no parent
 *        there.
 * @param hierarchyCodes the code of each hierarchy by its ID
 */
public record OrgUnitHistory(OrgUnit unit, List<OrgUnitState> versions, List<OrgUnitLink> parents,
        Map<String, String> hierarchyCodes) {
}

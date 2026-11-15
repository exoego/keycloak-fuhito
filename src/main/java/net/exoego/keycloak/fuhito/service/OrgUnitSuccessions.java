package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;

/**
 * The org units that an org unit follows, and the ones that follow it.
 *
 * @param predecessors the links to the units that the unit follows. Several of them mean a merger.
 * @param successors the links to the units that follow the unit. Several of them mean a split.
 */
public record OrgUnitSuccessions(List<OrgUnitSuccession> predecessors, List<OrgUnitSuccession> successors) {
}

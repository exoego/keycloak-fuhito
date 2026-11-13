package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.Qualification;

/**
 * A qualification with the code of its kind.
 *
 * @param qualification the qualification
 * @param kind the code of the qualification kind, or null when the kind was removed
 */
public record QualificationView(Qualification qualification, String kind) {
}

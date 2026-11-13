package net.exoego.keycloak.fuhito.domain;

/**
 * The parent of an org unit in one hierarchy for a period.
 * A unit with no link on a date is a root on that date.
 *
 * @param id the ID
 * @param hierarchyId the ID of the hierarchy
 * @param childId the ID of the child unit
 * @param parentId the ID of the parent unit
 * @param period the dates when the child is under the parent
 */
public record OrgUnitLink(String id, String hierarchyId, String childId, String parentId, ValidPeriod period) {
}

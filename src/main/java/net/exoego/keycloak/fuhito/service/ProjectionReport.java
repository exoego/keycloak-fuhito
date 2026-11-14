package net.exoego.keycloak.fuhito.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ProjectionReport",
        description = "What a rebuild of the projection changed in Keycloak.",
        requiredProperties = {"organizations", "groupsCreated", "groupsRenamed", "groupsMoved", "groupsRemoved", "membersJoined", "membersLeft", "organizationMembersAdded"})
public record ProjectionReport(
        @Schema(description = "The number of linked organizations that were projected.")
        int organizations,
        @Schema(description = "The number of groups that were created.")
        int groupsCreated,
        @Schema(description = "The number of groups that got a new name.")
        int groupsRenamed,
        @Schema(description = "The number of groups that got a new parent.")
        int groupsMoved,
        @Schema(description = "The number of groups that were removed.")
        int groupsRemoved,
        @Schema(description = "The number of group memberships that were added.")
        int membersJoined,
        @Schema(description = "The number of group memberships that were removed.")
        int membersLeft,
        @Schema(description = "The number of users who became members of an organization.")
        int organizationMembersAdded) {
}

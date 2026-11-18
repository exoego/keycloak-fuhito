package net.exoego.keycloak.fuhito.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "ProjectionReport",
        description = "What a rebuild of the projection changed in Keycloak.",
        requiredProperties = {"organizations", "groupsCreated", "groupsRenamed", "groupsMoved", "groupsRemoved", "membersJoined", "membersLeft", "organizationMembersAdded",
            "organizationMembersRemoved", "realmGroupTrees"})
public record ProjectionReport(
        @Schema(description = "The number of linked organizations that were projected. A run of the changes counts "
                + "the organizations whose groups it mirrored again.")
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
        int organizationMembersAdded,
        @Schema(description = "The number of users who were taken out of an organization that the projection had "
                + "added them to.")
        int organizationMembersRemoved,
        @Schema(description = "1 when the tree was mirrored into realm groups, 0 when the realm does not do it.")
        int realmGroupTrees,
        @Schema(description = "The number of entries of the change log that a run of the changes read. Missing for "
                + "a full rebuild.")
        Integer changeLogEntries,
        @Schema(description = "The number of users whose groups and organizations a run of the changes looked at "
                + "again. Missing for a full rebuild, and when a run of the changes fell back to one.")
        Integer usersLookedAt,
        @Schema(description = "The number of organizations that a run of the changes looked at, for its org units "
                + "and for its users. Missing for a full rebuild, and when a run of the changes fell back to one.")
        Integer organizationsLookedAt,
        @Schema(description = "True when a run of the changes ran a full rebuild instead. Missing for a full "
                + "rebuild that was asked for.")
        Boolean fellBack,
        @Schema(
                description = "Why a run of the changes fell back to a full rebuild. `no_earlier_run` means that no "
                        + "run was done before. `settings` means that the realm group or the use of organizations "
                        + "changed, or that the realm group cannot be read. `restore`, `hierarchy`, and `erasure` "
                        + "mean that such an entry is in the change log. `earlier_state` means that the change log "
                        + "does not tell an earlier state that the run needs, such as the user that a person was "
                        + "linked to. `unknown_entry` means an entry of a type that the run does not know.",
                enumeration = {"no_earlier_run", "settings", "restore", "hierarchy", "erasure", "earlier_state",
                    "unknown_entry"})
        String fallbackReason) {

    /**
     * Makes the report of a full rebuild.
     *
     * @param organizations the number of linked organizations that were projected
     * @param groupsCreated the number of groups that were created
     * @param groupsRenamed the number of groups that got a new name
     * @param groupsMoved the number of groups that got a new parent
     * @param groupsRemoved the number of groups that were removed
     * @param membersJoined the number of group memberships that were added
     * @param membersLeft the number of group memberships that were removed
     * @param organizationMembersAdded the number of users who became members of an organization
     * @param organizationMembersRemoved the number of users who were taken out of an organization
     * @param realmGroupTrees 1 when the tree was mirrored into realm groups, else 0
     */
    public ProjectionReport(int organizations, int groupsCreated, int groupsRenamed, int groupsMoved,
            int groupsRemoved, int membersJoined, int membersLeft, int organizationMembersAdded,
            int organizationMembersRemoved, int realmGroupTrees) {
        this(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved, membersJoined, membersLeft,
                organizationMembersAdded, organizationMembersRemoved, realmGroupTrees, null, null, null, null, null);
    }

    ProjectionReport ofChanges(int entries, int users, int organizationsSeen) {
        return new ProjectionReport(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved,
                membersJoined, membersLeft, organizationMembersAdded, organizationMembersRemoved, realmGroupTrees,
                entries, users, organizationsSeen, false, null);
    }

    ProjectionReport fellBackFrom(int entries, String reason) {
        return new ProjectionReport(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved,
                membersJoined, membersLeft, organizationMembersAdded, organizationMembersRemoved, realmGroupTrees,
                entries, null, null, true, reason);
    }
}

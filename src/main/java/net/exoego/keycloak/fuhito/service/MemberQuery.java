package net.exoego.keycloak.fuhito.service;

/**
 * The options of a request to list the members of an org unit. They are not checked yet.
 *
 * @param asOf the date as {@code YYYY-MM-DD}, or null for today
 * @param includeDescendants true to also list the members of every unit below
 * @param occupation the code of an occupation to keep, or null for all
 * @param jobTitle the code of a job title to keep, or null for all
 * @param status an employment status to keep, or null for all
 * @param first the number of members to skip, or null for 0
 * @param max the largest number of members to return, or null for 100
 */
public record MemberQuery(String asOf, boolean includeDescendants, String occupation, String jobTitle,
        String status, Integer first, Integer max) {
}

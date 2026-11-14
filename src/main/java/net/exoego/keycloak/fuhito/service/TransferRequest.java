package net.exoego.keycloak.fuhito.service;

/**
 * The values of a request to move a person to another employer. They are not checked yet.
 *
 * @param employerId the ID of the org unit that employs the person from the date
 * @param validFrom the first date at the new employer, as {@code YYYY-MM-DD}
 * @param staffNumber the number that the new employer gives to the person, or null
 * @param endReason the code of the end reason of the old employment, or null
 */
public record TransferRequest(String employerId, String validFrom, String staffNumber, String endReason) {
}

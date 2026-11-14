package net.exoego.keycloak.fuhito.keycloak;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds one name from the first name and the last name that Keycloak keeps for a user.
 */
final class UserNames {

    /**
     * The realm attribute that says which part comes first.
     */
    static final String ORDER_ATTRIBUTE = "fuhito.nameOrder";

    // In Japan and some other countries the family name comes first.
    private static final String FAMILY_FIRST = "family-first";

    private UserNames() {
    }

    static String of(String firstName, String lastName, String order) {
        Stream<String> parts = FAMILY_FIRST.equals(order) ? Stream.of(lastName, firstName)
                : Stream.of(firstName, lastName);
        return parts.filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
    }
}

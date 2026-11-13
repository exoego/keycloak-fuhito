package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;

/**
 * Number formats for responses.
 */
final class Numbers {

    private Numbers() {
    }

    // The database returns 100.00 where the request said 100. Both must look the same in a response.
    static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}

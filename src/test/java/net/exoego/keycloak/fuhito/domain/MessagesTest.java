package net.exoego.keycloak.fuhito.domain;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void fillsInTheValues() {
        assertEquals("The code \"hospital\" is already used for the kind UNIT_TYPE.",
                Messages.format("ref_item.code_taken", Map.of("kind", "UNIT_TYPE", "code", "hospital"), Locale.ENGLISH));
    }

    @Test
    void returnsTheCodeWhenNoMessageExists() {
        assertEquals("no.such.code", Messages.format("no.such.code", Map.of(), Locale.ENGLISH));
    }
}

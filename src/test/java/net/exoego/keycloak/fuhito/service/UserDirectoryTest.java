package net.exoego.keycloak.fuhito.service;

import java.util.Optional;

import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDirectoryTest {

    @Test
    void aDirectoryThatOnlyChecksIdsFindsNoUsernameAndKnowsWhoExists() {
        UserDirectory users = "known"::equals;

        assertEquals(Optional.empty(), users.findIdByUsername("known"));
        assertTrue(users.isKnown("known"));
        assertFalse(users.isKnown("other"));
    }
}

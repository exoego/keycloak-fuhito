package net.exoego.keycloak.fuhito.keycloak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserNamesTest {

    @Test
    void theGivenNameComesFirstUnlessTheRealmSaysOtherwise() {
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", null));
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", "given-first"));
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", "something else"));
        assertEquals("山田 太郎", UserNames.of("太郎", "山田", "family-first"));
    }

    @Test
    void aMissingPartIsLeftOut() {
        assertEquals("Taro", UserNames.of(" Taro ", null, null));
        assertEquals("山田", UserNames.of("  ", "山田", "family-first"));
        assertEquals("", UserNames.of(null, null, null));
    }
}

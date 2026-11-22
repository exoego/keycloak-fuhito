package net.exoego.keycloak.fuhito.keycloak;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakRequestContextTest {

    // Locale would give he for iw.
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
        "ja | ja",
        "iw | iw",
        "EN-us | EN-us",
        "ja-JP,ja;q=0.9,en;q=0.8 | ja-JP",
        "fr;q=0.5, iw;q=0.9, en;q=0.8 | iw",
        "en;q=0.5, ja | ja",
        "en;q=0.5, iw;Q=0.5 | en",
        " en-GB ; q=0.8 ,EN-us | EN-us",
        "*, he;q=0.9 | he",
        "fr;q=0, en;q=0.001 | en",
        "ja;q=2, en;q=0.5 | en",
        "ja;q=0.1234, en;q=.5, de;q=1.0 | de",
        "* | null",
        "'' | null",
        "null | null",
    })
    void picksTheFirstTagOfTheHighestWeight(String header, String language) {
        assertEquals(language, KeycloakRequestContext.nameLanguage(header));
    }
}

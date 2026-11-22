package net.exoego.keycloak.fuhito.keycloak;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        "en;q=0.5, iw;Q=0.9 | iw",
        "en-GB ; q=0.8 ,EN-us | EN-us",
        "'en-GB\t;\tq=0.8\t,\tEN-us' | EN-us",
        "*, he;q=0.9 | he",
        "*;q=0.5, he;q=0.4 | he",
        "fr;q=0, en;q=0.001 | en",
        "ja;q=0, en | en",
        "ja;q=0, en;q=0.0, fr;q=0.00, de;q=0.000 | null",
        "ja;q=0.5, en;q=1., de;q=1.000 | en",
        "ja;q=0., en;q=0.123 | en",
        "ja, , en | ja",
        ", ,ja;q=0.5, | ja",
        "zh-Hant-TW, x-klingon | zh-Hant-TW",
        "abcdefgh-12345678 | abcdefgh-12345678",
        "* | null",
        "'' | null",
        "' ,\t' | null",
        "null | null",
    })
    void picksTheFirstTagOfTheHighestWeightAboveZero(String header, String language) {
        assertEquals(language, KeycloakRequestContext.language(header));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ja;q=2, en;q=0.5 | ja;q=2",
        "ja;q=.5, en;q=0.4 | ja;q=.5",
        "ja;q=0.5555 | ja;q=0.5555",
        "en_US, ja;q=0.5 | en_US",
        "ja;level=1 | ja;level=1",
        "ja;q=1.001 | ja;q=1.001",
        "ja;q=0.5;q=0.4 | ja;q=0.5;q=0.4",
        "ja;q= 0.5 | ja;q= 0.5",
        "ja ;q =0.5 | ja ;q =0.5",
        "ja;q= | ja;q=",
        "ja; | ja;",
        "ja q=0.5 | ja q=0.5",
        "ja, en ; q=2 , fr | en ; q=2",
        "abcdefghi | abcdefghi",
        "en-abcdefghi | en-abcdefghi",
        "1ja | 1ja",
        "en- | en-",
        "en--US | en--US",
        "*-US | *-US",
        "en-* | en-*",
        "ｊａ | ｊａ",
    })
    void rejectsAnElementThatIsNotInTheFormOfHttp(String header, String element) {
        assertBad(header, element);
    }

    @Test
    void takesOnlySpacesAndTabsAroundAnElement() {
        assertBad("ja,　en", "　en");
        assertBad("ja, en", " en");
    }

    private static void assertBad(String header, String element) {
        FuhitoException error = assertThrows(FuhitoException.class, () -> KeycloakRequestContext.language(header));
        assertEquals("invalid.header", error.code());
        assertEquals(Map.of("field", "Accept-Language", "value", element), error.params());
    }
}

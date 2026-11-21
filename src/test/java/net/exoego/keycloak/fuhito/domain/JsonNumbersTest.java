package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.1", "1E+2", "0.30000000000000004", "1.7976931348623157E+308",
            "-1.7976931348623157E+308", "4.9E-324", "9007199254740992", "-9007199254740992", "1E+20"})
    void aNumberThatADoubleGivesBackIsTheDouble(String value) {
        assertEquals(Double.parseDouble(value), JsonNumbers.sameDouble(new BigDecimal(value)).orElseThrow());
        assertTrue(JsonNumbers.fitsDouble(new BigDecimal(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+400", "-1E+400", "1E-400", "1.7976931348623158E+308", "50.000000000000001",
            "9007199254740993", "12345678901234567890", "5E-324", "1E+999999999", "1E-999999999"})
    void aNumberThatADoubleChangesHasNoDouble(String value) {
        assertTrue(JsonNumbers.sameDouble(new BigDecimal(value)).isEmpty());
        assertFalse(JsonNumbers.fitsDouble(new BigDecimal(value)));
    }

    @Test
    void aNumberOfEachTypeOfJsonFitsADoubleWhenTheDoubleGivesItBack() {
        assertTrue(JsonNumbers.fitsDouble(Integer.MAX_VALUE));
        assertTrue(JsonNumbers.fitsDouble(9007199254740992L));
        assertFalse(JsonNumbers.fitsDouble(9007199254740993L));
        assertFalse(JsonNumbers.fitsDouble(Long.MAX_VALUE));
        assertTrue(JsonNumbers.fitsDouble(new BigInteger("100000000000000000000")));
        assertFalse(JsonNumbers.fitsDouble(new BigInteger("99999999999999999999")));
        assertTrue(JsonNumbers.fitsDouble(0.1));
        assertFalse(JsonNumbers.fitsDouble(Double.POSITIVE_INFINITY));
        assertFalse(JsonNumbers.fitsDouble(Double.NaN));
        assertFalse(JsonNumbers.fitsDouble(1.5f));
    }
}

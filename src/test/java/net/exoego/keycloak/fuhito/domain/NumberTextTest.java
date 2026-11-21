package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "7", "-7", "42", "1000", "9223372036854775807", "-9223372036854775808",
        "99999999999999999999"})
    void aWholeNumberHasTheFormOfJson(String text) {
        assertTrue(NumberText.isWhole(text));
        assertEquals(Optional.of(new BigDecimal(text)), NumberText.decimal(text));
    }

    // Java reads ５, ٥, and +5 as 5.
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "+5", " 5", "5 ", "\uFF15", "\u0665", "\uFF10\uFF15", "05", "00", "-",
        "--1", "-+1", "1.5", "1.0", "1e2", "1E2", "0x10", "1_000", "1,000", "Infinity", "NaN", "5\n"})
    void anotherTextIsNoWholeNumber(String text) {
        assertFalse(NumberText.isWhole(text));
        assertEquals(OptionalLong.empty(), NumberText.whole(text, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void readsAWholeNumberBetweenItsLimits() {
        assertEquals(OptionalLong.of(0), NumberText.whole("0", 0, 1000));
        assertEquals(OptionalLong.of(0), NumberText.whole("-0", 0, 1000));
        assertEquals(OptionalLong.of(1000), NumberText.whole("1000", 0, 1000));
        assertEquals(OptionalLong.of(-2147483648), NumberText.whole("-2147483648", Integer.MIN_VALUE,
                Integer.MAX_VALUE));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), NumberText.whole("9223372036854775807", Long.MIN_VALUE,
                Long.MAX_VALUE));
        assertEquals(OptionalLong.of(Long.MIN_VALUE), NumberText.whole("-9223372036854775808", Long.MIN_VALUE,
                Long.MAX_VALUE));
    }

    @Test
    void aWholeNumberOutsideItsLimitsIsNone() {
        assertEquals(OptionalLong.empty(), NumberText.whole("-1", 0, 1000));
        assertEquals(OptionalLong.empty(), NumberText.whole("1001", 0, 1000));
        assertEquals(OptionalLong.empty(), NumberText.whole("2147483648", Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(OptionalLong.empty(), NumberText.whole("9223372036854775808", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(OptionalLong.empty(), NumberText.whole("-9223372036854775809", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(OptionalLong.empty(), NumberText.whole("9".repeat(10_000), Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "50", "50.0", "50.10", "0.5", "-0.5", "5E1", "5e1", "5e+1", "5e-1", "1E400",
        "1.7976931348623157e308"})
    void aNumberHasTheFormOfJson(String text) {
        assertEquals(Optional.of(new BigDecimal(text)), NumberText.decimal(text));
    }

    @Test
    void readsANumberAsWritten() {
        assertEquals("50.10", NumberText.decimal("50.10").orElseThrow().toPlainString());
    }

    // BigDecimal reads ５０, +50, .5, and 50. as numbers.
    @ParameterizedTest
    @ValueSource(strings = {"", " 50", "50 ", "+50", "\uFF15\uFF10", "\u0665\u0660", ".5", "50.", "050", "00.5",
        "5e", "5e+", "5E1.5", "1,5", "Infinity", "NaN", "0x10", "-", "50%"})
    void anotherTextIsNoNumber(String text) {
        assertEquals(Optional.empty(), NumberText.decimal(text));
    }

    // BigDecimal holds an exponent from -2147483648 to 2147483647 only.
    @Test
    void aNumberWhoseExponentCannotBeReadIsNone() {
        assertEquals(Optional.empty(), NumberText.decimal("1e9999999999"));
        assertEquals(Optional.empty(), NumberText.decimal("1e-9999999999"));
    }
}

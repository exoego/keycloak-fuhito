package net.exoego.keycloak.fuhito.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageTest {

    @Test
    void aRequestWithoutValuesGetsTheFirstHundred() {
        assertEquals(new Page(0, 100), Page.of(null, null));
    }

    @Test
    void takesTheValuesAsTheyAre() {
        assertEquals(new Page(5, 20), Page.of(5, 20));
        assertEquals(new Page(0, 0), Page.of(0, 0));
        assertEquals(new Page(Integer.MAX_VALUE, 1000), Page.of(Integer.MAX_VALUE, 1000));
        assertEquals(new Page(7, 100), Page.of(7, null));
        assertEquals(new Page(0, 5), Page.of(null, 5));
    }

    // The filter of the query rejects such a number with 400, so a page never gets it.
    @Test
    void neverFixesANumberOutsideItsLimits() {
        assertThrows(IllegalArgumentException.class, () -> Page.of(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> Page.of(0, -1));
        assertThrows(IllegalArgumentException.class, () -> Page.of(0, 1001));
        assertThrows(IllegalArgumentException.class, () -> Page.of(0, Integer.MAX_VALUE));
    }
}

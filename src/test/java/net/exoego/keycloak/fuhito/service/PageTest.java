package net.exoego.keycloak.fuhito.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageTest {

    @Test
    void aRequestWithoutValuesGetsTheFirstHundred() {
        assertEquals(new Page(0, 100), Page.of(null, null));
    }

    @Test
    void keepsTheValuesInsideTheirLimits() {
        assertEquals(new Page(5, 20), Page.of(5, 20));
        assertEquals(new Page(0, 0), Page.of(-5, -1));
        assertEquals(new Page(0, 1000), Page.of(0, 1000));
        assertEquals(new Page(0, 1000), Page.of(0, 1001));
        assertEquals(new Page(0, 1000), Page.of(0, Integer.MAX_VALUE));
    }
}

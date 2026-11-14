package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.stream.IntStream;

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

    @Test
    void cutsAWholeList() {
        List<Integer> all = IntStream.range(0, 1500).boxed().toList();

        assertEquals(List.of(2, 3, 4), Page.of(2, 3).of(all));
        assertEquals(100, Page.of(null, null).of(all).size());
        assertEquals(1000, Page.of(null, 5000).of(all).size());
        assertEquals(List.of(1499), Page.of(1499, 10).of(all));
        assertEquals(List.of(), Page.of(1500, 10).of(all));
    }
}

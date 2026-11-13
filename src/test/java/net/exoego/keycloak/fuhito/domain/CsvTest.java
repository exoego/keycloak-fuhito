package net.exoego.keycloak.fuhito.domain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvTest {

    private static List<List<String>> cells(String text) {
        return Csv.parse(text).stream().map(Csv.Row::cells).toList();
    }

    private static List<Integer> lines(String text) {
        return Csv.parse(text).stream().map(Csv.Row::line).toList();
    }

    @Test
    void readsPlainRows() {
        assertEquals(List.of(List.of("a", "b", "c"), List.of("1", "", "3")), cells("a,b,c\n1,,3\n"));
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), cells("a,b\r\n1,2"));
        assertEquals(List.of(List.of("a"), List.of("1")), cells("a\r1\r"));
    }

    @Test
    void leavesOutTheByteOrderMarkAndRowsWithNoContent() {
        assertEquals(List.of(List.of("名称", "コード"), List.of("内科", "naika")),
                cells("﻿名称,コード\n\n , \n内科,naika\n\n"));
        assertEquals(List.of(1, 4), lines("﻿名称,コード\n\n , \n内科,naika\n\n"));
        assertEquals(List.of(), cells(""));
        assertEquals(List.of(), cells("﻿"));
    }

    @Test
    void readsQuotedCells() {
        assertEquals(List.of(List.of("a,b", "say \"hi\"", "")), cells("\"a,b\",\"say \"\"hi\"\"\",\"\""));
        assertEquals(List.of(List.of("two\nlines", "x"), List.of("next", "y")), cells("\"two\nlines\",x\nnext,y"));
        assertEquals(List.of(1, 3), lines("\"two\nlines\",x\nnext,y"));
        assertEquals(List.of(List.of("a\r\nb")), cells("\"a\r\nb\""));
    }

    @Test
    void aQuoteInsideAPlainCellIsKept() {
        assertEquals(List.of(List.of("5\" disk", "x")), cells("5\" disk,x"));
        assertEquals(List.of(List.of("quoted then more", "x")), cells("\"quoted\" then more,x"));
    }

    @Test
    void rejectsAQuoteWithNoEnd() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> Csv.parse("a,b\n\"open,1\n2,3"));

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("csv.open_quote", e.code());
        assertEquals(Map.of("line", "2"), e.params());
    }
}

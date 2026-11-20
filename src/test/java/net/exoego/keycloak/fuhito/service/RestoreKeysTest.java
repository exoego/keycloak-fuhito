package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreKeysTest {

    private static RealmExport terms(String... periods) {
        List<RealmExport.Terms> terms = new ArrayList<>();
        for (int i = 0; i < periods.length; i += 2) {
            terms.add(new RealmExport.Terms("t-" + i, periods[i], periods[i + 1], null, null, "ACTIVE", null, null,
                    null, Map.of()));
        }
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, null,
                List.of(new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null, "2020-04-01", null, null, null,
                        null, null, terms)), null, null, null, null, null, null, null);
    }

    private static RealmExport names(String firstTo, String secondFrom) {
        return new RealmExport(RealmExport.FORMAT, null, List.of(new RealmExport.Item("i-1", 1L, "JOB_TITLE",
                "chief", 0, Map.of(), true, false, false, false, false, List.of(
                        new RealmExport.ItemNames("n-1", null, firstTo, "ja", Map.of()),
                        new RealmExport.ItemNames("n-2", secondFrom, null, "ja", Map.of())))),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RealmExport orders(String first, String second) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(order("o-1", first), order("o-2", second)), null);
    }

    private static RealmExport.Order order(String id, String number) {
        return new RealmExport.Order(id, 1L, number, null, "X", null, null, "2024-04-01", null, null, null,
                "2024-03-01T00:00:00Z", null, null);
    }

    private static void assertError(String code, RealmExport document, String list, String id, String field,
            String other) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreKeys.require(document));
        assertEquals(code, e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field, "other", other), e.params());
    }

    @Test
    void aRowThatEndsWhenTheNextStartsDoesNotOverlapIt() {
        assertDoesNotThrow(() -> RestoreKeys.require(terms("2020-04-01", "2021-04-01", "2021-04-01", null)));
        assertError("restore.overlap", terms("2020-04-01", "2021-04-02", "2021-04-01", null), "employment-terms",
                "t-2", "validFrom", "t-0");
    }

    @Test
    void theRowThatStartsLaterIsTheOneThatFails() {
        assertError("restore.overlap", terms("2021-04-01", null, "2020-04-01", "2022-04-01"), "employment-terms",
                "t-0", "validFrom", "t-2");
        assertError("restore.overlap", terms("2020-04-01", null, "2020-04-01", null), "employment-terms", "t-2",
                "validFrom", "t-0");
    }

    @Test
    void aPeriodThatEndsBeforeItStartsFailsInTheWrite() {
        assertDoesNotThrow(() -> RestoreKeys.require(terms("2020-04-01", null, "2022-04-01", "2021-04-01")));
    }

    @Test
    void namesWithNoStartHoldFromTheStart() {
        assertDoesNotThrow(() -> RestoreKeys.require(names("2021-04-01", "2021-04-01")));
        assertError("restore.overlap", names("2021-04-01", "1900-01-01"), "ref-item-names", "n-2", "validFrom",
                "n-1");
    }

    @Test
    void aMissingKeyIsNoKey() {
        assertDoesNotThrow(() -> RestoreKeys.require(orders(null, null)));
        assertError("restore.duplicate", orders("N-1", "N-1"), "change-orders", "o-2", "number", "o-1");
    }
}

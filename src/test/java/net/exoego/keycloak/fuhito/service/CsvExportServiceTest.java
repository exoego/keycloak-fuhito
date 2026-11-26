package net.exoego.keycloak.fuhito.service;

import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvExportServiceTest {

    private static final OrgUnitName NAME = new OrgUnitName("病棟", null, null);

    @Test
    void aRowNeedsNoColumnsForThePrimaryLanguage() {
        assertDoesNotThrow(() -> CsvExportService.requireInHeader("u-1",
                new Localized<>("ja", Map.of("ja", NAME, "en", NAME)), Set.of("en")));
        assertDoesNotThrow(() -> CsvExportService.requireInHeader("u-1",
                new Localized<>("en", Map.of("en", NAME)), Set.of()));
    }

    @Test
    void aNameInALanguageThatTheHeaderLacksStopsTheExport() {
        FuhitoException error = assertThrows(FuhitoException.class, () -> CsvExportService.requireInHeader("u-1",
                new Localized<>("ja", Map.of("ja", NAME, "ko", NAME)), Set.of("en")));
        assertEquals(FuhitoException.Reason.CONFLICT, error.reason());
        assertEquals("csv.export_changed", error.code());
        assertEquals(Map.of("id", "u-1", "language", "ko"), error.params());
    }
}

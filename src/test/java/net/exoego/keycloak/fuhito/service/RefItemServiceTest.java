package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemServiceTest {

    private final MemoryRefItemStore store = new MemoryRefItemStore();
    private final RefItemService service = new RefItemService(store);

    private RefItem create(String code, Integer displayOrder) {
        return service.create(RefKind.UNIT_TYPE, code, code + " name", null, displayOrder, null);
    }

    @Test
    void createsAnActiveItemWithDefaults() {
        RefItem item = create("hospital", null);

        assertEquals(36, item.id().length());
        assertEquals(RefKind.UNIT_TYPE, item.kind());
        assertEquals("hospital", item.code());
        assertEquals("hospital name", item.name());
        assertEquals(Map.of(), item.names());
        assertEquals(0, item.displayOrder());
        assertEquals(Map.of(), item.attributes());
        assertTrue(item.active());
        assertEquals(item, service.get(item.id()));
    }

    @Test
    void keepsLocalizedNamesAndAttributes() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "ward", "病棟", Map.of("en", "Ward"), 3, Map.of("beds", true));

        assertEquals(Map.of("en", "Ward"), item.names());
        assertEquals(3, item.displayOrder());
        assertEquals(Map.of("beds", true), item.attributes());
    }

    @Test
    void rejectsACodeThatIsTaken() {
        create("hospital", null);

        FuhitoException e = assertThrows(FuhitoException.class, () -> create("hospital", null));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.code_taken", e.code());
        assertEquals(Map.of("kind", "UNIT_TYPE", "code", "hospital"), e.params());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.create(null, "a", "A", null, null, null)).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "bad code", "A", null, null, null)).code());
        assertEquals("invalid.name", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "a", " ", null, null, null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.list(null, false, Page.of(null, null))).code());
    }

    @Test
    void listsByDisplayOrderThenCodeWithoutInactiveItems() {
        RefItem b = create("b", 1);
        RefItem a = create("a", 1);
        RefItem first = create("z", 0);
        RefItem inactive = create("off", 0);
        service.update(inactive.id(), "off", null, 0, null, false);

        assertEquals(List.of(first, a, b), service.list(RefKind.UNIT_TYPE, false, Page.of(null, null)));
        assertEquals(4, service.list(RefKind.UNIT_TYPE, true, Page.of(null, null)).size());
        assertEquals(List.of(a), service.list(RefKind.UNIT_TYPE, false, Page.of(1, 1)));
    }

    @Test
    void updatesEverythingButKindAndCode() {
        RefItem item = create("dept", 5);

        RefItem changed = service.update(item.id(), "Department", Map.of("ja", "部"), 7, Map.of("k", "v"), false);

        assertEquals(item.id(), changed.id());
        assertEquals("dept", changed.code());
        assertEquals("Department", changed.name());
        assertEquals(Map.of("ja", "部"), changed.names());
        assertEquals(7, changed.displayOrder());
        assertEquals(Map.of("k", "v"), changed.attributes());
        assertFalse(changed.active());
        assertEquals(changed, service.get(item.id()));
    }

    @Test
    void updateFallsBackToDefaults() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "dept", "部", Map.of("en", "Dept"), 5, Map.of("k", "v"));

        RefItem changed = service.update(item.id(), "部", null, null, null, null);

        assertEquals(Map.of(), changed.names());
        assertEquals(0, changed.displayOrder());
        assertEquals(Map.of(), changed.attributes());
        assertTrue(changed.active());
    }

    @Test
    void deletesAnItem() {
        RefItem item = create("dept", null);

        service.delete(item.id());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(item.id()));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("ref_item.not_found", e.code());
    }

    @Test
    void doesNotDeleteAnItemInUse() {
        RefItem item = create("dept", null);
        store.inUse.add(item.id());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.delete(item.id()));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.in_use", e.code());
        assertEquals(item, service.get(item.id()));
    }

    @Test
    void rejectsAnUnknownId() {
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.update("nope", "x", null, null, null, null)).code());
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.delete("nope")).code());
    }

    @Test
    void anUpdateCanSayThatTheItemStaysActive() {
        RefItem item = create("dept", 5);

        assertTrue(service.update(item.id(), "Department", null, null, Map.of("k", "v"), true).active());
    }
}

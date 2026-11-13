package net.exoego.keycloak.fuhito.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemServiceTest {

    private static final class MemoryStore implements RefItemStore {
        private final Map<String, RefItem> items = new LinkedHashMap<>();

        @Override
        public List<RefItem> list(RefKind kind, boolean includeInactive) {
            return items.values().stream()
                    .filter(i -> i.kind() == kind && (includeInactive || i.active()))
                    .sorted(Comparator.comparingInt(RefItem::displayOrder).thenComparing(RefItem::code))
                    .toList();
        }

        @Override
        public Optional<RefItem> find(String id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public boolean exists(RefKind kind, String code) {
            return items.values().stream().anyMatch(i -> i.kind() == kind && i.code().equals(code));
        }

        @Override
        public void insert(RefItem item) {
            items.put(item.id(), item);
        }

        @Override
        public void update(RefItem item) {
            items.put(item.id(), item);
        }

        @Override
        public void delete(String id) {
            items.remove(id);
        }
    }

    private final RefItemService service = new RefItemService(new MemoryStore());

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
                () -> service.list(null, false)).code());
    }

    @Test
    void listsByDisplayOrderThenCodeWithoutInactiveItems() {
        RefItem b = create("b", 1);
        RefItem a = create("a", 1);
        RefItem first = create("z", 0);
        RefItem inactive = create("off", 0);
        service.update(inactive.id(), "off", null, 0, null, false);

        assertEquals(List.of(first, a, b), service.list(RefKind.UNIT_TYPE, false));
        assertEquals(4, service.list(RefKind.UNIT_TYPE, true).size());
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
    void rejectsAnUnknownId() {
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.update("nope", "x", null, null, null, null)).code());
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.delete("nope")).code());
    }
}

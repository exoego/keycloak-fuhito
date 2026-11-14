package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyServiceTest {

    private final MemoryHierarchyStore store = new MemoryHierarchyStore();
    private final HierarchyService service = new HierarchyService(store);

    private static FuhitoException rejected(Runnable call) {
        return assertThrows(FuhitoException.class, call::run);
    }

    @Test
    void aRealmAlwaysHasTheDefaultHierarchy() {
        List<Hierarchy> hierarchies = service.list(Page.of(null, null));

        assertEquals(List.of("default"), hierarchies.stream().map(Hierarchy::code).toList());
        assertTrue(hierarchies.getFirst().isDefault());
        assertEquals(hierarchies, service.list(Page.of(null, null)));
    }

    @Test
    void createsRenamesAndDeletesAHierarchy() {
        Hierarchy matrix = service.create("matrix", "Matrix");

        assertFalse(matrix.isDefault());
        assertEquals(matrix, service.get(matrix.id()));
        assertEquals(List.of("default", "matrix"),
                service.list(Page.of(null, null)).stream().map(Hierarchy::code).toList());
        assertEquals(List.of("matrix"), service.list(Page.of(1, 1)).stream().map(Hierarchy::code).toList());

        Hierarchy renamed = service.rename(matrix.id(), "Projects");
        assertEquals(new Hierarchy(matrix.id(), "matrix", "Projects"), renamed);
        assertEquals(renamed, service.get(matrix.id()));

        service.delete(matrix.id());
        assertEquals("hierarchy.not_found", rejected(() -> service.get(matrix.id())).code());
    }

    @Test
    void rejectsBadValuesAndATakenCode() {
        service.create("matrix", "Matrix");

        FuhitoException taken = rejected(() -> service.create("matrix", "Again"));
        assertEquals(FuhitoException.Reason.CONFLICT, taken.reason());
        assertEquals("hierarchy.code_taken", taken.code());
        assertEquals("hierarchy.code_taken", rejected(() -> service.create("default", "Mine")).code());
        assertEquals(FuhitoException.Reason.INVALID, rejected(() -> service.create("no spaces", "x")).reason());
        assertEquals(FuhitoException.Reason.INVALID, rejected(() -> service.create("ok", " ")).reason());
        String id = service.list(Page.of(1, 1)).getFirst().id();
        assertEquals(FuhitoException.Reason.INVALID, rejected(() -> service.rename(id, null)).reason());
        assertEquals(FuhitoException.Reason.NOT_FOUND, rejected(() -> service.rename("nope", "x")).reason());
    }

    @Test
    void theDefaultHierarchyAndAHierarchyWithLinksCannotBeDeleted() {
        String defaultId = service.list(Page.of(null, null)).getFirst().id();
        Hierarchy matrix = service.create("matrix", "Matrix");
        store.withLinks.add(matrix.id());

        assertEquals("hierarchy.default", rejected(() -> service.delete(defaultId)).code());
        FuhitoException inUse = rejected(() -> service.delete(matrix.id()));
        assertEquals(FuhitoException.Reason.CONFLICT, inUse.reason());
        assertEquals("hierarchy.in_use", inUse.code());
        assertEquals("hierarchy.not_found", rejected(() -> service.delete("nope")).code());
        assertEquals(matrix, service.get(matrix.id()));
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;
import net.exoego.keycloak.fuhito.store.ResourceRemoval.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemovalServiceTest {

    private final List<String> removed = new ArrayList<>();
    private final RemovalService service = new RemovalService(new ResourceRemoval() {
        @Override
        public boolean exists(Kind kind, String id) {
            return !id.equals("nope");
        }

        @Override
        public List<String> dependents(Kind kind, String id) {
            return id.equals("used") ? List.of("assignments", "children") : List.of();
        }

        @Override
        public void remove(Kind kind, String id) {
            removed.add(kind + " " + id);
        }
    });

    @Test
    void removesWhatNothingRefersTo() {
        service.remove(Kind.ASSIGNMENT, "a1");

        assertEquals(List.of("ASSIGNMENT a1"), removed);
        FuhitoException used = assertThrows(FuhitoException.class, () -> service.remove(Kind.ORG_UNIT, "used"));
        assertEquals("org_unit.in_use", used.code());
        assertEquals(Map.of("id", "used", "uses", "assignments, children"), used.params());
        assertEquals("employment.not_found", assertThrows(FuhitoException.class,
                () -> service.remove(Kind.EMPLOYMENT, "nope")).code());
        assertEquals(List.of("ASSIGNMENT a1"), removed);
    }
}

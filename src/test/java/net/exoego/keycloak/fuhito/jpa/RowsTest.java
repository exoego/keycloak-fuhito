package net.exoego.keycloak.fuhito.jpa;

import java.lang.reflect.Proxy;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowsTest {

    private record Row(String realmId) {
    }

    // An entity manager that knows only how to find a row by its ID.
    private static EntityManager finding(Map<String, Row> rows) {
        return (EntityManager) Proxy.newProxyInstance(RowsTest.class.getClassLoader(),
                new Class<?>[] {EntityManager.class}, (proxy, method, args) -> {
                    assertEquals("find", method.getName());
                    return rows.get(args[1]);
                });
    }

    @Test
    void returnsARowOfTheRealm() {
        Row row = new Row("realm-a");

        assertEquals(row, Rows.require(finding(Map.of("1", row)), Row.class, "1", "realm-a", Row::realmId));
    }

    @Test
    void refusesAMissingRowAndARowOfAnotherRealm() {
        EntityManager em = finding(Map.of("1", new Row("realm-b")));

        assertEquals("No such Row in the realm: 1", assertThrows(IllegalStateException.class,
                () -> Rows.require(em, Row.class, "1", "realm-a", Row::realmId)).getMessage());
        assertEquals("No such Row in the realm: 2", assertThrows(IllegalStateException.class,
                () -> Rows.require(em, Row.class, "2", "realm-a", Row::realmId)).getMessage());
    }
}

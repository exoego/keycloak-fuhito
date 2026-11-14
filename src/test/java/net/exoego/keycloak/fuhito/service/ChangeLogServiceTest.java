package net.exoego.keycloak.fuhito.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ChangeLogStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeLogServiceTest {

    private static final class MemoryChangeLogStore implements ChangeLogStore {
        private final List<ChangeLogEntry> entries = new ArrayList<>();

        @Override
        public void append(ChangeLogEntry entry) {
            entries.add(new ChangeLogEntry(entry.id(), entries.size() + 1, entry.recordedAt(), entry.actorUserId(),
                    entry.resourceType(), entry.resourceId(), entry.operation(), entry.resourcePath(),
                    entry.changeOrderId(), entry.representation()));
        }

        @Override
        public List<ChangeLogEntry> list(long after, String resourceType, String resourceId, int first, int max) {
            return entries.stream()
                    .filter(e -> e.sequence() > after)
                    .filter(e -> resourceType == null || e.resourceType().equals(resourceType))
                    .filter(e -> resourceId == null || e.resourceId().equals(resourceId))
                    .skip(first).limit(max).toList();
        }

        @Override
        public List<ChangeLogEntry> listByChangeOrder(String changeOrderId, int first, int max) {
            return entries.stream().filter(e -> changeOrderId.equals(e.changeOrderId())).skip(first).limit(max)
                    .toList();
        }

        @Override
        public boolean existsForChangeOrder(String changeOrderId) {
            return entries.stream().anyMatch(e -> changeOrderId.equals(e.changeOrderId()));
        }
    }

    private final MemoryChangeLogStore store = new MemoryChangeLogStore();
    private final MemoryChangeOrderStore changeOrders = new MemoryChangeOrderStore();
    private final ChangeLogService service = new ChangeLogService(store, changeOrders);

    private void write(String resourceId, String operation, String changeOrderId) {
        store.append(new ChangeLogEntry("entry-" + store.entries.size(), 0, Instant.parse("2026-04-01T00:00:00Z"),
                "admin", "FUHITO_ORG_UNIT", resourceId, operation, "fuhito/org-units/" + resourceId, changeOrderId,
                "{\"id\":\"" + resourceId + "\"}"));
    }

    private static List<String> summary(List<ChangeLogEntry> entries) {
        return entries.stream().map(e -> e.sequence() + " " + e.resourceId() + " " + e.operation()).toList();
    }

    @Test
    void listsTheWritesToAResourceOldestFirst() {
        write("u1", "CREATE", null);
        write("u2", "CREATE", null);
        write("u1", "UPDATE", "order");

        List<ChangeLogEntry> entries = service.list(null, "FUHITO_ORG_UNIT", "u1", null, null);

        assertEquals(List.of("1 u1 CREATE", "3 u1 UPDATE"), summary(entries));
        ChangeLogEntry update = entries.get(1);
        assertEquals("entry-2", update.id());
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), update.recordedAt());
        assertEquals("admin", update.actorUserId());
        assertEquals("FUHITO_ORG_UNIT", update.resourceType());
        assertEquals("fuhito/org-units/u1", update.resourcePath());
        assertEquals("order", update.changeOrderId());
        assertEquals("{\"id\":\"u1\"}", update.representation());
        assertEquals(List.of(), service.list(null, "FUHITO_REF_ITEM", "u1", null, null));
    }

    @Test
    void pagesTheEntries() {
        for (int i = 0; i < 3; i++) {
            write("u1", "UPDATE", null);
        }

        assertEquals(List.of("2 u1 UPDATE"), summary(service.list(null, "FUHITO_ORG_UNIT", "u1", 1, 1)));
        assertEquals(3, service.list(null, "FUHITO_ORG_UNIT", "u1", -5, null).size());
        assertEquals(0, service.list(null, "FUHITO_ORG_UNIT", "u1", null, -1).size());
    }

    @Test
    void listsTheWritesOfAChangeOrder() {
        changeOrders.insert(new ChangeOrder("order", null, null, "Order", null, null,
                LocalDate.of(2026, 4, 1), null, null, null, Instant.EPOCH));
        write("u1", "CREATE", "order");
        write("u2", "CREATE", null);
        write("u3", "UPDATE", "order");

        assertEquals(List.of("1 u1 CREATE", "3 u3 UPDATE"), summary(service.ofChangeOrder("order", null, null)));
        assertEquals(List.of("3 u3 UPDATE"), summary(service.ofChangeOrder("order", 1, 5)));
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.ofChangeOrder("nope", null, null));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("change_order.not_found", e.code());
    }

    @Test
    void followsEveryWriteAfterASequenceNumber() {
        write("u1", "CREATE", null);
        write("u2", "CREATE", null);
        write("u1", "UPDATE", null);

        assertEquals(List.of("1 u1 CREATE", "2 u2 CREATE", "3 u1 UPDATE"),
                summary(service.list(null, null, null, null, null)));
        assertEquals(List.of("2 u2 CREATE", "3 u1 UPDATE"), summary(service.list(1L, null, null, null, null)));
        assertEquals(List.of("3 u1 UPDATE"), summary(service.list(1L, null, "u1", null, null)));
        assertEquals(List.of("2 u2 CREATE"), summary(service.list(1L, "FUHITO_ORG_UNIT", null, null, 1)));
        assertEquals(List.of(), service.list(3L, null, null, null, null));
    }

    @Test
    void returnsAtMostAThousandEntries() {
        for (int i = 0; i < 1001; i++) {
            write("u1", "UPDATE", null);
        }

        assertEquals(100, service.list(null, null, null, null, null).size());
        assertEquals(1000, service.list(null, null, null, null, 5000).size());
    }
}

package net.exoego.keycloak.fuhito.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeLogServiceTest {

    private final MemoryChangeLogStore store = new MemoryChangeLogStore();
    private final MemoryChangeOrderStore changeOrders = new MemoryChangeOrderStore();
    private final ChangeLogService service = new ChangeLogService(store, changeOrders);

    private static final Instant APRIL = Instant.parse("2026-04-01T00:00:00Z");

    private void write(String resourceId, String operation, String changeOrderId) {
        store.append(ChangeLogEntry.write("entry-" + store.entries.size(), APRIL, "admin",
                ResourceType.FUHITO_ORG_UNIT, resourceId, operation, "fuhito/org-units/" + resourceId,
                changeOrderId));
    }

    private void propagated(String resourceId, Instant at) {
        store.append(ChangeLogEntry.propagated("entry-" + store.entries.size(), at,
                DerivedValue.KEYCLOAK_ORGANIZATION_ID, resourceId,
                ChangeLogEntry.Cause.KEYCLOAK_ORGANIZATION_REMOVED, null, null));
    }

    private static void assertError(FuhitoException.Reason reason, String code, Map<String, String> params,
            Executable read) {
        FuhitoException e = assertThrows(FuhitoException.class, read);
        assertEquals(List.of(reason, code, params), List.of(e.reason(), e.code(), e.params()));
    }

    private static List<String> summary(List<ChangeLogEntry> entries) {
        return entries.stream().map(e -> e.sequence() + " " + e.resourceId() + " " + e.operation()).toList();
    }

    @Test
    void listsTheWritesToAResourceOldestFirst() {
        write("u1", "CREATE", null);
        write("u2", "CREATE", null);
        write("u1", "UPDATE", "order");

        List<ChangeLogEntry> entries = service.list(null, "FUHITO_ORG_UNIT", "u1", null, null, null);

        assertEquals(List.of("1 u1 CREATE", "3 u1 UPDATE"), summary(entries));
        ChangeLogEntry update = entries.get(1);
        assertEquals("entry-2", update.id());
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), update.recordedAt());
        assertEquals("admin", update.actorUserId());
        assertEquals(ResourceType.FUHITO_ORG_UNIT, update.resourceType());
        assertEquals("fuhito/org-units/u1", update.resourcePath());
        assertEquals("order", update.changeOrderId());
        assertEquals(List.of(), service.list(null, "FUHITO_REF_ITEM", "u1", null, null, null));
    }

    @Test
    void pagesTheEntries() {
        for (int i = 0; i < 3; i++) {
            write("u1", "UPDATE", null);
        }

        assertEquals(List.of("2 u1 UPDATE"), summary(service.list(null, "FUHITO_ORG_UNIT", "u1", null, 1, 1)));
        assertEquals(3, service.list(null, "FUHITO_ORG_UNIT", "u1", null, 0, null).size());
        assertEquals(0, service.list(null, "FUHITO_ORG_UNIT", "u1", null, null, 0).size());
    }

    @Test
    void listsTheWritesOfAChangeOrder() {
        changeOrders.insert(new ChangeOrder("order", null, null, "Order", null, null,
                LocalDate.of(2026, 4, 1), null, null, null, Instant.EPOCH, null, null, 1));
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
                summary(service.list(null, null, null, null, null, null)));
        assertEquals(List.of("2 u2 CREATE", "3 u1 UPDATE"), summary(service.list(1L, null, null, null, null, null)));
        assertEquals(List.of("3 u1 UPDATE"), summary(service.list(1L, null, "u1", null, null, null)));
        assertEquals(List.of("2 u2 CREATE"), summary(service.list(1L, "FUHITO_ORG_UNIT", null, null, null, 1)));
        assertEquals(List.of(), service.list(3L, null, null, null, null, null));
    }

    @Test
    void returnsAtMostAThousandEntries() {
        for (int i = 0; i < 1001; i++) {
            write("u1", "UPDATE", null);
        }

        assertEquals(100, service.list(null, null, null, null, null, null).size());
        assertEquals(1000, service.list(null, null, null, null, null, 1000).size());
        // The check of the query rejects a larger number before the service runs.
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, null, null, 1001));
    }

    @Test
    void rejectsATypeThatTheChangeLogDoesNotWrite() {
        write("u1", "CREATE", null);

        for (String type : List.of("ORG_UNIT", "fuhito_org_unit", "FUHITO_UNKNOWN")) {
            for (Executable read : List.<Executable>of(() -> service.list(null, type, null, null, null, null),
                    () -> service.count(type, null, null))) {
                FuhitoException e = assertThrows(FuhitoException.class, read, type);
                assertEquals(FuhitoException.Reason.INVALID, e.reason());
                assertEquals("invalid.choice", e.code());
                assertEquals(Map.of("field", "resourceType", "value", type), e.params());
            }
        }
        assertEquals(1, service.count("FUHITO_ORG_UNIT", null, null));
        assertEquals(0, service.count("FUHITO_RESTORE", null, null));
        assertEquals(1, service.count(null, null, null));
    }

    @Test
    void aFilterLeavesOutThePropagatedChangesOrTheWrites() {
        write("u1", "CREATE", null);
        propagated("u1", APRIL);
        write("u2", "CREATE", null);

        assertEquals(List.of("1 u1 CREATE", "3 u2 CREATE"), summary(service.list(null, null, null, "WRITE", null,
                null)));
        List<ChangeLogEntry> propagated = service.list(null, null, null, "PROPAGATED", null, null);
        assertEquals(List.of("2 u1 UPDATE"), summary(propagated));
        assertEquals(List.of(2, 1, 3), List.of(service.count(null, null, "WRITE"),
                service.count("FUHITO_ORG_UNIT", "u1", "PROPAGATED"), service.count(null, null, null)).stream()
                .map(Long::intValue).toList());
        for (Executable read : List.<Executable>of(() -> service.list(null, null, null, "write", null, null),
                () -> service.count(null, null, "READ"))) {
            FuhitoException e = assertThrows(FuhitoException.class, read);
            assertEquals(List.of("invalid.choice", "kind"), List.of(e.code(), e.params().get("field")));
        }
    }

    @Test
    void aReadAfterANumberThatNoEntryHadFails() {
        write("u1", "CREATE", null);
        write("u1", "UPDATE", null);

        assertEquals(List.of(), service.list(2L, null, null, null, null, null));
        assertEquals(2, service.list(0L, null, null, null, null, null).size());
        assertError(FuhitoException.Reason.INVALID, "invalid.out_of_range",
                Map.of("field", "after", "min", "0", "max", "2"),
                () -> service.list(3L, null, null, "WRITE", null, null));
        assertError(FuhitoException.Reason.INVALID, "invalid.out_of_range",
                Map.of("field", "after", "min", "0", "max", "0"),
                () -> new ChangeLogService(new MemoryChangeLogStore(), changeOrders).list(1L, null, null, null,
                        null, null));
    }

    @Test
    void aReadAfterARemovedPropagatedChangeFailsUnlessItLeavesThemOut() {
        write("u1", "CREATE", null);
        propagated("u1", APRIL);
        write("u2", "CREATE", null);
        propagated("u2", APRIL.plusSeconds(60));
        propagated("u1", APRIL.plusSeconds(120));

        assertEquals(2, store.removePropagated(APRIL.plusSeconds(61)));

        for (String kind : new String[] {null, "PROPAGATED"}) {
            assertError(FuhitoException.Reason.GONE, "change_log.cursor_expired",
                    Map.of("after", "3", "keptAfter", "4"), () -> service.list(3L, null, null, kind, null, null));
            assertError(FuhitoException.Reason.GONE, "change_log.cursor_expired",
                    Map.of("after", "1", "keptAfter", "4"), () -> service.list(1L, null, "u2", kind, null, null));
        }
        assertEquals(List.of("3 u2 CREATE"), summary(service.list(1L, null, null, "WRITE", null, null)));
        assertEquals(List.of("5 u1 UPDATE"), summary(service.list(4L, null, null, null, null, null)));
        assertEquals(List.of("1 u1 CREATE", "3 u2 CREATE", "5 u1 UPDATE"),
                summary(service.list(null, null, null, null, null, null)));
        assertEquals(3, service.list(0L, null, null, null, null, null).size());
    }

    // A removal can end while the entries are read. The read then fails, as if the removal had ended first.
    @Test
    void aRemovalThatEndsDuringTheReadFailsIt() {
        MemoryChangeLogStore removing = new MemoryChangeLogStore() {
            @Override
            public List<ChangeLogEntry> list(long after, ResourceType resourceType, String resourceId,
                    ChangeLogEntry.Kind kind, int first, int max) {
                List<ChangeLogEntry> read = super.list(after, resourceType, resourceId, kind, first, max);
                removePropagated(APRIL.plusSeconds(1));
                return read;
            }
        };
        ChangeLogService reading = new ChangeLogService(removing, changeOrders);
        for (int i = 0; i < 3; i++) {
            removing.append(ChangeLogEntry.propagated("p" + i, APRIL, DerivedValue.KEYCLOAK_ORGANIZATION_ID, "u1",
                    ChangeLogEntry.Cause.KEYCLOAK_ORGANIZATION_REMOVED, null, null));
        }

        assertError(FuhitoException.Reason.GONE, "change_log.cursor_expired", Map.of("after", "1", "keptAfter", "3"),
                () -> reading.list(1L, null, null, null, null, null));
    }
}

package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T01:02:03.123456789Z");

    private final MemoryChangeOrderStore store = new MemoryChangeOrderStore();
    private final ChangeOrderService service = new ChangeOrderService(store, Set.of("director", "hr")::contains,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static ChangeOrderValues titled(String title, String effectiveDate) {
        return new ChangeOrderValues(null, null, title, null, null, effectiveDate, null, null, null);
    }

    private FuhitoException failure(ChangeOrderValues request) {
        return assertThrows(FuhitoException.class, () -> service.create(request));
    }

    @Test
    void createsAnOrderWithOnlyATitleAndAnEffectiveDate() {
        ChangeOrder order = service.create(titled(" April transfers ", "2026-04-01"));

        assertEquals(36, order.id().length());
        assertEquals("April transfers", order.title());
        assertEquals(LocalDate.of(2026, 4, 1), order.effectiveDate());
        assertNull(order.number());
        assertNull(order.kind());
        assertNull(order.note());
        assertNull(order.orderDate());
        assertNull(order.approverUserId());
        assertNull(order.requesterUserId());
        assertNull(order.externalReference());
        assertEquals(Instant.parse("2026-03-20T01:02:03.123Z"), order.createdAt());
        assertEquals(order, service.get(order.id()));
    }

    @Test
    void createsAnOrderWithEveryValue() {
        ChangeOrder order = service.create(new ChangeOrderValues("2026-0042", "reorganization", "Merge of two wards",
                "Board decision of March.", "2026-03-15", "2026-04-01", "director", "hr", "DOC-9"));

        assertEquals("2026-0042", order.number());
        assertEquals("reorganization", order.kind());
        assertEquals("Board decision of March.", order.note());
        assertEquals(LocalDate.of(2026, 3, 15), order.orderDate());
        assertEquals("director", order.approverUserId());
        assertEquals("hr", order.requesterUserId());
        assertEquals("DOC-9", order.externalReference());
    }

    @Test
    void updatesEverythingButTheTimeOfCreation() {
        ChangeOrder created = service.create(new ChangeOrderValues("N-1", "personnel", "Old", "note", "2026-03-15",
                "2026-04-01", "director", "hr", "DOC-1"));

        ChangeOrder changed = service.update(created.id(), new ChangeOrderValues("N-1", null, "New", null, null,
                "2026-05-01", null, null, null));

        assertEquals(created.id(), changed.id());
        assertEquals("New", changed.title());
        assertEquals(LocalDate.of(2026, 5, 1), changed.effectiveDate());
        assertNull(changed.kind());
        assertNull(changed.approverUserId());
        assertEquals(created.createdAt(), changed.createdAt());
        assertEquals(changed, service.get(created.id()));
    }

    @Test
    void theNumberIsUniqueInTheRealm() {
        ChangeOrder first = service.create(new ChangeOrderValues("N-1", null, "First", null, null, "2026-04-01", null,
                null, null));
        ChangeOrder second = service.create(titled("Second", "2026-04-01"));
        ChangeOrderValues taken = new ChangeOrderValues("N-1", null, "Other", null, null, "2026-04-01", null, null,
                null);

        FuhitoException e = failure(taken);
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("change_order.number_taken", e.code());
        assertEquals(Map.of("number", "N-1"), e.params());
        assertEquals("change_order.number_taken",
                assertThrows(FuhitoException.class, () -> service.update(second.id(), taken)).code());
        // An order may keep its own number.
        assertEquals("Other", service.update(first.id(), taken).title());
    }

    @Test
    void listsByEffectiveDateNewestFirst() {
        service.create(titled("April", "2026-04-01"));
        service.create(titled("October", "2026-10-01"));
        service.create(titled("Next April", "2027-04-01"));

        assertEquals(List.of("Next April", "October", "April"), titles(service.list(null, null)));
        assertEquals(List.of("October"), titles(service.list("2026-04-02", "2027-04-01")));
        assertEquals(List.of("October", "April"), titles(service.list(null, "2027-04-01")));
        assertEquals(List.of("Next April"), titles(service.list("2027-04-01", null)));
        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> service.list("x", null)).code());
    }

    private static List<String> titles(List<ChangeOrder> orders) {
        return orders.stream().map(ChangeOrder::title).toList();
    }

    @Test
    void deletesAnOrder() {
        ChangeOrder order = service.create(titled("Gone", "2026-04-01"));

        service.delete(order.id());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(order.id()));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("change_order.not_found", e.code());
        assertEquals("change_order.not_found",
                assertThrows(FuhitoException.class, () -> service.delete(order.id())).code());
        assertEquals("change_order.not_found", assertThrows(FuhitoException.class,
                () -> service.update(order.id(), titled("x", "2026-04-01"))).code());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.create(null)).code());
        assertEquals("invalid.required", failure(titled(null, "2026-04-01")).code());
        assertEquals("invalid.name", failure(titled(" ", "2026-04-01")).code());
        assertEquals("invalid.required", failure(titled("x", null)).code());
        assertEquals("invalid.date", failure(titled("x", "April")).code());
        assertEquals("invalid.date", failure(new ChangeOrderValues(null, null, "x", null, "soon", "2026-04-01", null,
                null, null)).code());
        assertEquals("invalid.code", failure(new ChangeOrderValues("no spaces", null, "x", null, null, "2026-04-01",
                null, null, null)).code());
        assertEquals("invalid.code", failure(new ChangeOrderValues(null, "bad kind", "x", null, null, "2026-04-01",
                null, null, null)).code());
        FuhitoException approver = failure(new ChangeOrderValues(null, null, "x", null, null, "2026-04-01", "nobody",
                null, null));
        assertEquals("change_order.user_unknown", approver.code());
        assertEquals(Map.of("field", "approverUserId", "userId", "nobody"), approver.params());
        assertEquals("requesterUserId", failure(new ChangeOrderValues(null, null, "x", null, null, "2026-04-01",
                "director", "nobody", null)).params().get("field"));
        assertTrue(store.orders.isEmpty());
    }
}

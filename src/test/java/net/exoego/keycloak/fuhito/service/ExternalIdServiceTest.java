package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.ExternalId.Kind;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalIdServiceTest {

    private final MemoryExternalIdStore store = new MemoryExternalIdStore();
    private final ExternalIdService service = new ExternalIdService(store);

    @BeforeEach
    void createUnits() {
        store.resource(Kind.ORG_UNIT, "ward", "hr", "W-1");
        store.resource(Kind.ORG_UNIT, "clinic", null, null);
    }

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    private static List<String> summary(List<ExternalIdService.View> views) {
        return views.stream().map(v -> v.source() + "=" + v.externalId() + (v.own() ? " own" : "")).toList();
    }

    @Test
    void aResourceGetsTheIdsOfMoreSystemsAndIsFoundByEach() {
        ExternalIdService.View payroll = service.put("ORG_UNIT", "ward", "payroll", "P-9");
        service.put("ORG_UNIT", "ward", "budget", "B-3");

        assertFalse(payroll.own());
        assertEquals(List.of("hr=W-1 own", "budget=B-3", "payroll=P-9"), summary(service.list("ORG_UNIT", "ward")));
        assertEquals("ward", service.find("ORG_UNIT", "payroll", "P-9").resourceId());
        assertTrue(service.find("ORG_UNIT", "hr", "W-1").own());
        assertEquals(List.of(), summary(service.list("ORG_UNIT", "clinic")));

        // A second put for the same system replaces the ID.
        assertEquals(payroll.rowId(), service.put("ORG_UNIT", "ward", "payroll", "P-10").rowId());
        assertEquals("external_id.unknown", code(() -> service.find("ORG_UNIT", "payroll", "P-9")));
        assertEquals("payroll", service.remove("ORG_UNIT", "ward", "payroll").source());
        assertEquals(List.of("hr=W-1 own", "budget=B-3"), summary(service.list("ORG_UNIT", "ward")));
    }

    @Test
    void oneIdNamesOneResource() {
        service.put("ORG_UNIT", "ward", "payroll", "P-9");

        assertEquals("external_id.taken", code(() -> service.put("ORG_UNIT", "clinic", "payroll", "P-9")));
        assertEquals("external_id.taken", code(() -> service.put("ORG_UNIT", "clinic", "hr", "W-1")));
        assertEquals("external_id.own_source", code(() -> service.put("ORG_UNIT", "ward", "hr", "W-2")));
        assertEquals("external_id.resource_unknown", code(() -> service.put("ORG_UNIT", "nope", "payroll",
                "P-1")));
        assertEquals("external_id.resource_unknown", code(() -> service.put("PERSON", "ward", "payroll", "P-1")));
        assertEquals("invalid.code", code(() -> service.put("ORG_UNIT", "clinic", "pay roll", "P-1")));
        assertEquals("external_id.bad_value", code(() -> service.put("ORG_UNIT", "clinic", "payroll", " ")));
        assertEquals("invalid.choice", code(() -> service.list("ROOM", "ward")));
        assertEquals("external_id.not_found", code(() -> service.remove("ORG_UNIT", "clinic", "payroll")));

        // The ID of a resource that is gone is free again.
        store.pairs.get(Kind.ORG_UNIT).remove("ward");
        assertEquals("external_id.unknown", code(() -> service.find("ORG_UNIT", "payroll", "P-9")));
        assertEquals("clinic", service.put("ORG_UNIT", "clinic", "payroll", "P-9").resourceId());
        assertEquals(1, store.rows.size());
    }
}

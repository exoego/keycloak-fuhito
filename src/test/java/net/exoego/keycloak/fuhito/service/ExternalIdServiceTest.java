package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.ExternalId.Kind;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalIdServiceTest {

    private final MemoryExternalIdStore store = new MemoryExternalIdStore();
    private final ExternalIdService service = new ExternalIdService(store);

    @BeforeEach
    void createUnits() {
        store.resource(Kind.ORG_UNIT, "ward", "hr");
        store.insert(new ExternalId("row-w", Kind.ORG_UNIT, "ward", "hr", "W-1"));
        store.resource(Kind.ORG_UNIT, "clinic", null);
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

        // A second put for the same system replaces the ID, also the one of the system that made the resource.
        assertEquals(payroll.rowId(), service.put("ORG_UNIT", "ward", "payroll", "P-10").rowId());
        assertEquals("external_id.unknown", code(() -> service.find("ORG_UNIT", "payroll", "P-9")));
        assertTrue(service.put("ORG_UNIT", "ward", "hr", "W-2").own());
        assertEquals("payroll", service.remove("ORG_UNIT", "ward", "payroll").source());
        assertEquals(List.of("hr=W-2 own", "budget=B-3"), summary(service.list("ORG_UNIT", "ward")));
    }

    @Test
    void theIdOfTheSystemThatMadeAResourceCanGoAndTheResourceKeepsTheSystem() {
        service.remove("ORG_UNIT", "ward", "hr");

        assertEquals(List.of(), summary(service.list("ORG_UNIT", "ward")));
        assertEquals("external_id.unknown", code(() -> service.find("ORG_UNIT", "hr", "W-1")));
        assertEquals(Optional.of(new ExternalId.Origin("hr")), store.originOf(Kind.ORG_UNIT, "ward"));
        // Another resource may take the ID now.
        assertEquals("clinic", service.put("ORG_UNIT", "clinic", "hr", "W-1").resourceId());
    }

    @Test
    void oneIdNamesOneResource() {
        service.put("ORG_UNIT", "ward", "payroll", "P-9");

        assertEquals("external_id.taken", code(() -> service.put("ORG_UNIT", "clinic", "payroll", "P-9")));
        assertEquals("external_id.taken", code(() -> service.put("ORG_UNIT", "clinic", "hr", "W-1")));
        assertEquals("external_id.resource_unknown", code(() -> service.put("ORG_UNIT", "nope", "payroll",
                "P-1")));
        assertEquals("external_id.resource_unknown", code(() -> service.put("PERSON", "ward", "payroll", "P-1")));
        assertEquals("invalid.code", code(() -> service.put("ORG_UNIT", "clinic", "pay roll", "P-1")));
        assertEquals("invalid.required", code(() -> service.put("ORG_UNIT", "clinic", null, "P-1")));
        assertEquals("external_id.bad_value", code(() -> service.put("ORG_UNIT", "clinic", "payroll", " ")));
        assertEquals("invalid.choice", code(() -> service.list("ROOM", "ward")));
        assertEquals("external_id.not_found", code(() -> service.remove("ORG_UNIT", "clinic", "payroll")));
        // The same ID in another kind of resource is another ID.
        store.resource(Kind.PERSON, "nurse", null);
        assertEquals("nurse", service.put("PERSON", "nurse", "payroll", "P-9").resourceId());
    }

    @Test
    void readsTheIdsOfManyResourcesAtOnce() {
        service.put("ORG_UNIT", "ward", "payroll", "P-9");
        service.put("ORG_UNIT", "clinic", "payroll", "P-8");

        ExternalIdLists lists = service.lists(Kind.ORG_UNIT, List.of("ward", "clinic", "nope"));

        assertEquals(1, store.batchReads);
        assertEquals(List.of("hr W-1", "payroll P-9"), lists.of("ward", "hr").stream()
                .map(id -> id.source() + " " + id.externalId()).toList());
        assertEquals("P-8", lists.externalIdOf("clinic", "payroll"));
        assertNull(lists.externalIdOf("clinic", null));
        assertEquals(List.of(), lists.of("nope", null));
        assertEquals(List.of(), ExternalIdLists.NONE.of("ward", "hr"));
        assertEquals(Optional.of("ward"), service.resourceOf(Kind.ORG_UNIT, "payroll", "P-9"));
        assertEquals(Optional.of("P-9"), service.externalIdOf(Kind.ORG_UNIT, "ward", "payroll"));
        assertEquals(Optional.empty(), service.externalIdOf(Kind.ORG_UNIT, "ward", "budget"));
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.ItemNames.english;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefItemInAttributesTest {

    private final MemoryRefItemStore store = new MemoryRefItemStore();
    private final AttributeDefinitionService definitions =
            new AttributeDefinitionService(new MemoryAttributeDefinitionStore(),
                    new MemoryAttributeValueStore(), store, kind -> true);
    private final RefItemService service = new RefItemService(store, definitions, kind -> true);

    private RefItem create(String kind, String code) {
        return service.create(kind, code, "en", english(code), null, null, RefItemService.Marks.NONE);
    }

    @Test
    void anItemWhoseCodeAnAttributeOfItsKindHasStays() {
        RefItem site = create("site", "tokyo");
        definitions.create("ASSIGNMENT", "sites", "REF_ITEM", "site", true, null, null);
        store.attributeValues.add(List.of("ASSIGNMENT", "sites", "tokyo"));

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.delete(site.id()));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.in_use", e.code());
        assertEquals(Map.of("id", site.id(), "target", "ASSIGNMENT", "name", "sites"), e.params());
        assertEquals(site, service.get(site.id()));
    }

    @Test
    void onlyAnAttributeOfTheTypeRefItemForTheKindCounts() {
        create("site", "tokyo");
        RefItem region = create("region", "tokyo");
        definitions.create("PERSON", "note", "STRING", null, null, null, null);
        definitions.create("PERSON", "region", "REF_ITEM", "region", null, null, null);
        definitions.create("ASSIGNMENT", "sites", "REF_ITEM", "site", true, null, null);
        store.attributeValues.add(List.of("PERSON", "note", "tokyo"));
        store.attributeValues.add(List.of("ASSIGNMENT", "sites", "tokyo"));

        service.delete(region.id());

        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.get(region.id())).code());
    }

    @Test
    void anItemThatNoValueHasGoes() {
        RefItem site = create("site", "osaka");
        definitions.create("PERSON", "site", "REF_ITEM", "site", null, null, null);
        store.attributeValues.add(List.of("PERSON", "site", "tokyo"));

        service.delete(site.id());

        assertEquals("ref_item.not_found", assertThrows(FuhitoException.class, () -> service.get(site.id())).code());
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.store.RealmKindStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmKindServiceTest {

    private final List<RealmKind> rows = new ArrayList<>();
    private final RealmKindStore store = new RealmKindStore() {
        @Override
        public List<RealmKind> list() {
            return rows.stream().sorted(java.util.Comparator.comparing(RealmKind::code)).toList();
        }

        @Override
        public Optional<RealmKind> findByCode(String code) {
            return rows.stream().filter(kind -> kind.code().equals(code)).findFirst();
        }

        @Override
        public Optional<String> codeOf(String id) {
            return rows.stream().filter(kind -> kind.id().equals(id)).map(RealmKind::code).findFirst();
        }

        @Override
        public void insert(RealmKind kind) {
            rows.add(kind);
        }

        @Override
        public void update(RealmKind kind) {
            rows.replaceAll(stored -> stored.id().equals(kind.id()) ? kind : stored);
        }

        @Override
        public void delete(String id) {
            rows.removeIf(kind -> kind.id().equals(id));
        }
    };
    private final MemoryRefItemStore items = new MemoryRefItemStore();
    private final MemoryAttributeDefinitionStore definitionStore = new MemoryAttributeDefinitionStore();
    private final RealmKindService kinds = new RealmKindService(store, items, definitionStore);
    private final RefItemService refItems = new RefItemService(items, AttributeRules.NONE, kinds::isKnown);

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void aRealmAddsAKindAndItsItemsAreReferenceItems() {
        RealmKindService.View location = kinds.create("work-location", "ja",
                Map.of("ja", new RefKindName("勤務地"), "en-us", new RefKindName("Work location")));

        assertFalse(location.fixed());
        assertEquals(new Localized<>("ja", Map.of("ja", new RefKindName("勤務地"), "en-us",
                new RefKindName("Work location"))), location.names());
        assertEquals(RefKind.values().length + 1, kinds.list().size());
        assertTrue(kinds.list().getFirst().fixed());
        assertEquals("work-location", kinds.list().getLast().code());
        assertTrue(kinds.isKnown("work-location"));
        assertTrue(kinds.isKnown("GRADE"));
        assertFalse(kinds.isKnown("cost-center"));

        refItems.create("work-location", "tokyo", "ja", ItemNames.japanese("東京"), null, null,
                RefItemService.Marks.NONE);
        assertEquals(List.of("tokyo"), refItems.list("work-location", false, null, Page.of(null, null)).stream()
                .map(item -> item.code()).toList());
        assertEquals("invalid.kind", code(() -> refItems.create("cost-center", "c1", "en", ItemNames.english("C1"), null, null,
                RefItemService.Marks.NONE)));
        assertEquals("ref_item.lateral_kind", code(() -> refItems.create("work-location", "osaka", "ja", ItemNames.japanese("大阪"), null,
                null, new RefItemService.Marks(null, null, true))));

        // An attribute can point to the kind of the realm.
        AttributeDefinitionService definitions = new AttributeDefinitionService(definitionStore,
                new MemoryAttributeValueStore(), items, kinds::isKnown);
        AttributeDefinition workLocation = definitions.create("ASSIGNMENT", "workLocation", "REF_ITEM",
                "work-location", null, null, null).definition();
        assertEquals(Map.of("workLocation", "tokyo"), definitions.check(Target.ASSIGNMENT,
                Map.of("workLocation", "tokyo")));
        assertEquals("attribute.bad_value", code(() -> definitions.check(Target.ASSIGNMENT,
                Map.of("workLocation", "osaka"))));
        assertEquals("invalid.choice", code(() -> definitions.create("ASSIGNMENT", "cost", "REF_ITEM", "cost-center",
                null, null, null)));

        assertEquals("ref_kind.in_use", code(() -> kinds.delete("work-location")));
        refItems.delete(refItems.list("work-location", true, null, Page.of(null, null)).getFirst().id());
        // The definition still has the kind.
        FuhitoException used = assertThrows(FuhitoException.class, () -> kinds.delete("work-location"));
        assertEquals("ref_kind.in_use", used.code());
        assertEquals(Map.of("code", "work-location", "target", "ASSIGNMENT", "name", "workLocation"), used.params());
        definitions.delete(workLocation.id(), false);
        kinds.delete("work-location");
        assertFalse(kinds.isKnown("work-location"));
    }

    @Test
    void aRenameGivesTheKindOtherNames() {
        kinds.create("site", "ja", japanese("拠点"));

        RealmKindService.View renamed = kinds.rename("site", "en",
                Map.of("en", new RefKindName("Site"), "ja", new RefKindName("拠点")));

        assertEquals(new Localized<>("en", Map.of("en", new RefKindName("Site"), "ja", new RefKindName("拠点"))),
                renamed.names());
        assertEquals(renamed, kinds.get("site"));
        assertEquals("ref_kind.not_found", code(() -> kinds.rename("nope", "ja", japanese("X"))));
        assertEquals("invalid.primary_language", code(() -> kinds.rename("site", "en", japanese("拠点"))));
    }

    @Test
    void rejectsABadKind() {
        kinds.create("site", "ja", japanese("拠点"));

        assertEquals("ref_kind.code_taken", code(() -> kinds.create("site", "ja", japanese("拠点"))));
        assertEquals("ref_kind.code_taken", code(() -> kinds.create("grade", "ja", japanese("等級"))));
        assertEquals("invalid.code", code(() -> kinds.create("a b", "ja", japanese("X"))));
        assertEquals("invalid.code", code(() -> kinds.create("x".repeat(33), "ja", japanese("X"))));
        assertEquals("invalid.name", code(() -> kinds.create("other", "ja", japanese(" "))));
        assertEquals("invalid.required", code(() -> kinds.create("other", null, japanese("X"))));
        assertEquals("ref_kind.not_found", code(() -> kinds.delete("nope")));
    }

    private static Map<String, RefKindName> japanese(String name) {
        return Map.of("ja", new RefKindName(name));
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefKind;
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
    private final RealmKindService kinds = new RealmKindService(store, items);
    private final RefItemService refItems = new RefItemService(items, AttributeRules.NONE, kinds::isKnown);

    private static String code(Runnable call) {
        return assertThrows(FuhitoException.class, call::run).code();
    }

    @Test
    void aRealmAddsAKindAndItsItemsAreReferenceItems() {
        RealmKindService.View location = kinds.create("work-location", "勤務地", Map.of("en", "Work location"));

        assertFalse(location.fixed());
        assertEquals(RefKind.values().length + 1, kinds.list().size());
        assertTrue(kinds.list().getFirst().fixed());
        assertEquals("work-location", kinds.list().getLast().code());
        assertTrue(kinds.isKnown("work-location"));
        assertTrue(kinds.isKnown("GRADE"));
        assertFalse(kinds.isKnown("cost-center"));

        refItems.create("work-location", "tokyo", "東京", null, null, null, RefItemService.Marks.NONE);
        assertEquals(List.of("tokyo"), refItems.list("work-location", false, null, Page.of(null, null)).stream()
                .map(item -> item.code()).toList());
        assertEquals("invalid.kind", code(() -> refItems.create("cost-center", "c1", "C1", null, null, null,
                RefItemService.Marks.NONE)));
        assertEquals("ref_item.lateral_kind", code(() -> refItems.create("work-location", "osaka", "大阪", null, null,
                null, new RefItemService.Marks(null, null, true))));

        // An attribute can point to the kind of the realm.
        AttributeDefinitionService definitions = new AttributeDefinitionService(new MemoryAttributeDefinitionStore(),
                items, kinds::isKnown);
        definitions.create("ASSIGNMENT", "workLocation", "REF_ITEM", "work-location", null, null);
        assertEquals(Map.of("workLocation", "tokyo"), definitions.check(Target.ASSIGNMENT,
                Map.of("workLocation", "tokyo")));
        assertEquals("attribute.bad_value", code(() -> definitions.check(Target.ASSIGNMENT,
                Map.of("workLocation", "osaka"))));
        assertEquals("invalid.choice", code(() -> definitions.create("ASSIGNMENT", "cost", "REF_ITEM", "cost-center",
                null, null)));

        assertEquals("ref_kind.in_use", code(() -> kinds.delete("work-location")));
        refItems.delete(refItems.list("work-location", true, null, Page.of(null, null)).getFirst().id());
        kinds.delete("work-location");
        assertFalse(kinds.isKnown("work-location"));
    }

    @Test
    void rejectsABadKind() {
        kinds.create("site", "拠点", null);

        assertEquals("ref_kind.code_taken", code(() -> kinds.create("site", "拠点", null)));
        assertEquals("ref_kind.code_taken", code(() -> kinds.create("grade", "等級", null)));
        assertEquals("invalid.code", code(() -> kinds.create("a b", "X", null)));
        assertEquals("invalid.code", code(() -> kinds.create("x".repeat(33), "X", null)));
        assertEquals("invalid.name", code(() -> kinds.create("other", " ", null)));
        assertEquals("ref_kind.not_found", code(() -> kinds.delete("nope")));
    }
}

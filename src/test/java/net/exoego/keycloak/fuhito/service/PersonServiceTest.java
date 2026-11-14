package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.service.PersonView.NameSource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonServiceTest {

    // The names that Keycloak has. A user with an empty name exists and has no name.
    private static final Map<String, String> KEYCLOAK_NAMES = Map.of("taro", "Taro Yamada", "hanako",
            "Hanako Sato", "nameless", "");

    private static final UserDirectory USERS = new UserDirectory() {
        @Override
        public boolean exists(String userId) {
            return KEYCLOAK_NAMES.containsKey(userId);
        }

        @Override
        public Optional<String> displayName(String userId) {
            return Optional.ofNullable(KEYCLOAK_NAMES.get(userId)).filter(name -> !name.isEmpty());
        }
    };

    private final MemoryPersonStore store = new MemoryPersonStore();
    private final PersonService service = new PersonService(store, USERS);

    private static PersonValues named(String name) {
        return new PersonValues(null, name, null, null, null, null, null);
    }

    private static PersonValues linked(String userId, String name) {
        return new PersonValues(userId, name, null, null, null, null, null);
    }

    private FuhitoException failure(Runnable call) {
        return assertThrows(FuhitoException.class, call::run);
    }

    @Test
    void createsAPersonWithNoUserAndEveryValue() {
        PersonView view = service.create(new PersonValues(null, " 山田 太郎 ", Map.of("en", "Taro Yamada"),
                " やまだ たろう ", Map.of("派遣元", "A社"), "P-1", "hr"));

        Person person = view.person();
        assertEquals(36, person.id().length());
        assertNull(person.userId());
        assertEquals("山田 太郎", person.name());
        assertEquals(Map.of("en", "Taro Yamada"), person.names());
        assertEquals("やまだ たろう", person.phoneticName());
        assertEquals(Map.of("派遣元", "A社"), person.attributes());
        assertEquals("P-1", person.externalId());
        assertEquals("hr", person.source());
        assertEquals("山田 太郎", view.name());
        assertEquals(NameSource.RECORD, view.nameSource());
        assertEquals(view, service.get(person.id()));
    }

    @Test
    void aPersonWithNoNameOfItsOwnShowsTheNameOfKeycloak() {
        PersonView view = service.create(linked("taro", null));

        assertNull(view.person().name());
        assertEquals("Taro Yamada", view.name());
        assertEquals(NameSource.KEYCLOAK, view.nameSource());
        assertEquals(Map.of(), view.person().names());
        assertEquals(Map.of(), view.person().attributes());
    }

    @Test
    void theNameOfTheRecordWinsOverTheNameOfKeycloak() {
        PersonView view = service.create(linked("taro", "山田 太郎"));

        assertEquals("山田 太郎", view.name());
        assertEquals(NameSource.RECORD, view.nameSource());
    }

    @Test
    void aLinkedPersonCanHaveNoNameAtAll() {
        PersonView view = service.create(linked("nameless", null));

        assertNull(view.name());
        assertNull(view.nameSource());
    }

    @Test
    void replacesTheNamesAndTheLinkAndKeepsTheExternalId() {
        String id = service.create(new PersonValues(null, "山田 太郎", null, null, Map.of("a", 1), "P-1", "hr"))
                .person().id();

        // The person gets an account later.
        PersonView changed = service.replace(id, new PersonValues("taro", null, Map.of("en", "Taro"), "やまだ",
                null, "ignored", "ignored"));

        assertEquals("taro", changed.person().userId());
        assertEquals("Taro Yamada", changed.name());
        assertEquals(NameSource.KEYCLOAK, changed.nameSource());
        assertEquals(Map.of("en", "Taro"), changed.person().names());
        assertEquals("やまだ", changed.person().phoneticName());
        assertEquals(Map.of(), changed.person().attributes());
        assertEquals("P-1", changed.person().externalId());
        assertEquals("hr", changed.person().source());

        // Keeping the same link is no conflict. Leaving it out removes it.
        assertEquals("taro", service.replace(id, linked("taro", "山田")).person().userId());
        assertNull(service.replace(id, named("山田")).person().userId());
    }

    @Test
    void listsEveryoneInPagesOrOnlyThePersonOfAUser() {
        String a = service.create(named("A")).person().id();
        String b = service.create(linked("hanako", null)).person().id();
        String c = service.create(named("C")).person().id();
        List<String> sorted = List.of(a, b, c).stream().sorted().toList();

        assertEquals(sorted, service.list(null, null, null).stream().map(v -> v.person().id()).toList());
        assertEquals(sorted.subList(1, 2),
                service.list(null, 1, 1).stream().map(v -> v.person().id()).toList());
        assertEquals(sorted, service.list(null, -5, 100).stream().map(v -> v.person().id()).toList());
        assertTrue(service.list(null, 0, -1).isEmpty());
        assertEquals(List.of(b), service.list("hanako", null, null).stream().map(v -> v.person().id()).toList());
        assertTrue(service.list("taro", null, null).isEmpty());
    }

    @Test
    void deletesAPerson() {
        String id = service.create(named("A")).person().id();

        service.delete(id);

        assertEquals("person.not_found", failure(() -> service.get(id)).code());
        assertEquals("person.not_found", failure(() -> service.delete(id)).code());
    }

    @Test
    void rejectsBadValues() {
        service.create(new PersonValues("taro", null, null, null, null, "P-1", "hr"));
        String other = service.create(named("Other")).person().id();

        assertEquals("invalid.required", failure(() -> service.create(null)).code());
        assertEquals("invalid.required", failure(() -> service.replace(other, null)).code());
        assertEquals("person.name_required", failure(() -> service.create(named(null))).code());
        assertEquals("person.name_required", failure(() -> service.replace(other, named(null))).code());
        assertEquals("invalid.name", failure(() -> service.create(named("  "))).code());
        assertEquals("person.user_unknown", failure(() -> service.create(linked("nobody", "X"))).code());
        FuhitoException taken = failure(() -> service.create(linked("taro", "X")));
        assertEquals("person.user_taken", taken.code());
        assertEquals("taro", taken.params().get("userId"));
        assertEquals("person.user_taken", failure(() -> service.replace(other, linked("taro", "X"))).code());
        assertEquals("person.external_id_taken", failure(
                () -> service.create(new PersonValues(null, "X", null, null, null, "P-1", "hr"))).code());
        assertEquals("invalid.code", failure(
                () -> service.create(new PersonValues(null, "X", null, null, null, "P-2", "bad source"))).code());
        assertEquals("invalid.language_tag", failure(
                () -> service.create(new PersonValues(null, "X", Map.of("not a tag", "X"), null, null, null,
                        null))).code());
        assertEquals("person.not_found", failure(() -> service.replace("nope", named("X"))).code());
    }

    @Test
    void theSameExternalIdIsFineForAnotherSourceOrWithNoSource() {
        service.create(new PersonValues(null, "A", null, null, null, "P-1", "hr"));

        assertEquals("payroll", service.create(new PersonValues(null, "B", null, null, null, "P-1", "payroll"))
                .person().source());
        assertNull(service.create(new PersonValues(null, "C", null, null, null, "P-1", null)).person().source());
        assertNull(service.create(new PersonValues(null, "D", null, null, null, null, "hr")).person().externalId());
    }
}

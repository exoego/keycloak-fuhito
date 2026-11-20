package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.PersonView.NameSource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.PeopleNames.japanese;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
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
    private final MemoryEmploymentStore employments = new MemoryEmploymentStore();
    private final PersonService service = new PersonService(store, externalIds, USERS,
            id -> !employments.findByPerson(id).isEmpty());

    private static PersonValues named(String name) {
        return linked(null, name);
    }

    private static PersonValues linked(String userId, String name) {
        return name == null ? new PersonValues(userId, null, null, null, null, null)
                : new PersonValues(userId, "ja", japanese(name), null, null, null);
    }

    private static PersonValues withIds(String name, String externalId, String source) {
        return new PersonValues(null, "ja", japanese(name), null, externalId, source);
    }

    private FuhitoException failure(Runnable call) {
        return assertThrows(FuhitoException.class, call::run);
    }

    @Test
    void createsAPersonWithNoUserAndEveryValue() {
        PersonView view = service.create(new PersonValues(null, "JA", Map.of("JA",
                new PersonName(" 山田 太郎 ", " やまだ たろう "), "en", new PersonName("Taro Yamada", null)),
                Map.of("派遣元", "A社"), "P-1", "hr"));

        Person person = view.person();
        assertEquals(36, person.id().length());
        assertNull(person.userId());
        assertEquals(new Localized<>("ja", Map.of("ja", new PersonName("山田 太郎", "やまだ たろう"), "en",
                new PersonName("Taro Yamada", null))), person.names());
        assertEquals(Map.of("派遣元", "A社"), person.attributes());
        assertEquals(Optional.of(person.id()), externalIds.idOf(ExternalId.Kind.PERSON, "hr", "P-1"));
        assertEquals("hr", person.source());
        assertEquals(new PersonName("山田 太郎", "やまだ たろう"), view.names(null));
        assertEquals(new PersonName("Taro Yamada", null), view.names(Locale.US));
        assertEquals(NameSource.RECORD, view.nameSource());
        assertEquals(view, service.get(person.id()));
    }

    @Test
    void aPersonWithNoNameOfItsOwnShowsTheNameOfKeycloak() {
        PersonView view = service.create(linked("taro", null));

        assertNull(view.person().names());
        assertEquals(new PersonName("Taro Yamada", null), view.names(Locale.JAPANESE));
        assertEquals(NameSource.KEYCLOAK, view.nameSource());
        assertEquals(Map.of(), view.person().attributes());
        // A request with no languages has no names either.
        assertNull(service.create(new PersonValues("hanako", null, Map.of(), null, null, null)).person().names());
    }

    @Test
    void theNameOfTheRecordWinsOverTheNameOfKeycloak() {
        PersonView view = service.create(linked("taro", "山田 太郎"));

        assertEquals("山田 太郎", view.names(Locale.ENGLISH).name());
        assertEquals(NameSource.RECORD, view.nameSource());
    }

    @Test
    void aLinkedPersonCanHaveNoNameAtAll() {
        PersonView view = service.create(linked("nameless", null));

        assertNull(view.names(null));
        assertNull(view.nameSource());
    }

    @Test
    void replacesTheNamesAndTheLinkAndAddsTheIdOfAnotherSystem() {
        String id = service.create(new PersonValues(null, "ja", japanese("山田 太郎"), Map.of("a", 1), "P-1", "hr"))
                .person().id();

        // The person gets an account later.
        PersonView changed = service.replace(id, new PersonValues("taro", "en",
                Map.of("en", new PersonName("Taro", "やまだ")), null, "E-9", "payroll"));

        assertEquals("taro", changed.person().userId());
        assertEquals(Localized.of("en", new PersonName("Taro", "やまだ")), changed.person().names());
        assertEquals(NameSource.RECORD, changed.nameSource());
        assertEquals(Map.of(), changed.person().attributes());
        assertEquals(Optional.of(id), externalIds.idOf(ExternalId.Kind.PERSON, "hr", "P-1"));
        assertEquals(Optional.of(id), externalIds.idOf(ExternalId.Kind.PERSON, "payroll", "E-9"));
        assertEquals("hr", changed.person().source());

        // With no names of its own, the person shows the name of Keycloak again.
        PersonView nameless = service.replace(id, linked("taro", null));
        assertNull(nameless.person().names());
        assertEquals(NameSource.KEYCLOAK, nameless.nameSource());

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
        assertEquals(1, service.count("hanako", null, null));
        // The user is one more filter, and the paging values work with it.
        assertTrue(service.list("hanako", "A", null, null, null, null).isEmpty());
        assertEquals(0, service.count("hanako", "A", null));
        assertTrue(service.list("hanako", 1, null).isEmpty());
        assertTrue(service.list("hanako", null, 0).isEmpty());
        assertEquals("invalid.cursor", failure(() -> service.list("hanako", null, null, "!")).code());

        // A cursor goes on after the person that it names.
        assertEquals(sorted.subList(1, 3), service.list(null, null, null, Cursor.encode(Cursor.PEOPLE, sorted.get(0))).stream()
                .map(v -> v.person().id()).toList());
        assertEquals(sorted.subList(2, 3), service.list(null, null, 1, Cursor.encode(Cursor.PEOPLE, sorted.get(1))).stream()
                .map(v -> v.person().id()).toList());
        assertTrue(service.list(null, null, null, Cursor.encode(Cursor.PEOPLE, sorted.get(2))).isEmpty());
        assertEquals("invalid.cursor", failure(() -> service.list(null, null, null, "!")).code());
        assertEquals("invalid.cursor_with_first",
                failure(() -> service.list(null, 1, null, Cursor.encode(Cursor.PEOPLE, sorted.get(0)))).code());
    }

    @Test
    void deletesAPerson() {
        String id = service.create(named("A")).person().id();

        service.delete(id);

        assertEquals("person.not_found", failure(() -> service.get(id)).code());
        assertEquals("person.not_found", failure(() -> service.delete(id)).code());
    }

    @Test
    void findsThePersonOfAUserOrCreatesIt() {
        PersonService.Resolved first = service.forUser("taro");
        PersonService.Resolved second = service.forUser("taro");

        assertEquals("taro", first.person().userId());
        assertEquals(first.person(), first.created().person());
        assertEquals("Taro Yamada", first.created().names(null).name());
        assertEquals(first.person(), second.person());
        assertNull(second.created());
        assertEquals("person.user_unknown", failure(() -> service.forUser("nobody")).code());
        assertEquals("invalid.required", failure(() -> service.forUser(null)).code());
    }

    @Test
    void aPersonWithAnEmploymentCannotBeDeleted() {
        String id = service.forUser("taro").person().id();
        employments.insert(new Employment("e1", id, "taro", Employer.legalEntity("employer"),
                ValidPeriod.startingAt(LocalDate.parse("2020-04-01")), null, null, null, null, 1));

        assertEquals("person.in_use", failure(() -> service.delete(id)).code());
    }

    @Test
    void rejectsBadValues() {
        service.create(new PersonValues("taro", null, null, null, "P-1", "hr"));
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
        assertEquals("external_id.taken", failure(() -> service.create(withIds("X", "P-1", "hr"))).code());
        assertEquals("invalid.code", failure(() -> service.create(withIds("X", "P-2", "bad source"))).code());
        assertEquals("invalid.language_tag", failure(() -> service.create(new PersonValues(null, "ja",
                Map.of("ja", new PersonName("X", null), "not a tag", new PersonName("X", null)), null, null,
                null))).code());
        assertEquals("invalid.required", failure(() -> service.create(new PersonValues(null, null,
                japanese("X"), null, null, null))).code());
        assertEquals("invalid.primary_language", failure(() -> service.create(new PersonValues("hanako", "en",
                Map.of(), null, null, null))).code());
        assertEquals("invalid.name", failure(() -> service.create(new PersonValues(null, "ja",
                Map.of("ja", new PersonName("X", "x".repeat(256))), null, null, null))).code());
        assertEquals("person.not_found", failure(() -> service.replace("nope", named("X"))).code());
    }

    @Test
    void theSameExternalIdIsFineForAnotherSourceAndNeedsASource() {
        service.create(withIds("A", "P-1", "hr"));

        assertEquals("payroll", service.create(withIds("B", "P-1", "payroll")).person().source());
        assertEquals("invalid.required", failure(() -> service.create(withIds("C", "P-1", null))).code());
        String withSourceOnly = service.create(withIds("D", null, "hr")).person().id();
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.PERSON, withSourceOnly));
    }
}

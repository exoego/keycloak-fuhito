package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.store.PersonStore;

final class MemoryPersonStore implements PersonStore {
    private final Map<String, Person> people = new TreeMap<>();
    private boolean everyIdIsAPerson;

    /** The records of each person whose read gives the user of the person. */
    final Map<String, List<PropagatedChange>> readsOfUser = new HashMap<>();

    /** The people whose records had their versions counted up, in order. */
    final List<String> countedUp = new ArrayList<>();

    /** A store with one person for each user. The ID of the person is the ID of the user. */
    static MemoryPersonStore linked(Collection<String> userIds) {
        MemoryPersonStore store = new MemoryPersonStore();
        userIds.forEach(id -> store.insert(of(id)));
        return store;
    }

    /** A store where any ID is a person that is linked to the user with the same ID. */
    static MemoryPersonStore anyUser() {
        MemoryPersonStore store = new MemoryPersonStore();
        store.everyIdIsAPerson = true;
        return store;
    }

    private static Person of(String id) {
        return new Person(id, id, null, Map.of(), null, 1);
    }

    @Override
    public Optional<Person> find(String id) {
        if (everyIdIsAPerson) {
            people.putIfAbsent(id, of(id));
        }
        return Optional.ofNullable(people.get(id));
    }

    @Override
    public Optional<Person> findByUser(String userId) {
        return people.values().stream().filter(p -> userId.equals(p.userId())).findFirst();
    }

    @Override
    public List<Person> list(String afterId, int first, int max) {
        return people.values().stream()
                .sorted(java.util.Comparator.comparing(Person::id))
                .filter(person -> afterId == null || person.id().compareTo(afterId) > 0)
                .skip(first).limit(max).toList();
    }

    @Override
    public List<Person> search(String userId, String text, String staffNumber, String afterId, int first,
            int max) {
        // Only the users and the names. The staff numbers are in the store of employments.
        return list(afterId, 0, Integer.MAX_VALUE).stream()
                .filter(person -> userId == null || userId.equals(person.userId()))
                .filter(person -> text == null || (person.names() != null && person.names().byLanguage().values()
                        .stream()
                        .flatMap(names -> Stream.of(names.name(), names.phoneticName()))
                        .anyMatch(name -> name != null && name.toLowerCase(Locale.ROOT)
                                .contains(text.toLowerCase(Locale.ROOT)))))
                .skip(first).limit(max).toList();
    }

    @Override
    public long count(String userId, String text, String staffNumber) {
        return search(userId, text, staffNumber, null, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public void insert(Person person) {
        people.put(person.id(), person);
    }

    @Override
    public void update(Person person) {
        people.put(person.id(), person);
    }

    @Override
    public void delete(String id) {
        people.remove(id);
    }

    @Override
    public List<PropagatedChange> countUpReadsOfUser(String personId) {
        countedUp.add(personId);
        return readsOfUser.getOrDefault(personId, List.of());
    }
}

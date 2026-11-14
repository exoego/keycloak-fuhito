package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.store.PersonStore;

final class MemoryPersonStore implements PersonStore {
    private final Map<String, Person> people = new TreeMap<>();
    private boolean everyIdIsAPerson;

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
        return new Person(id, id, null, Map.of(), null, Map.of(), null, null);
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
    public Optional<Person> findByExternalId(String source, String externalId) {
        return people.values().stream()
                .filter(p -> Objects.equals(source, p.source()) && Objects.equals(externalId, p.externalId()))
                .findFirst();
    }

    @Override
    public List<Person> list(int first, int max) {
        return people.values().stream().skip(first).limit(max).toList();
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
}

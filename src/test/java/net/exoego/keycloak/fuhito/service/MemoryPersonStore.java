package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.store.PersonStore;

final class MemoryPersonStore implements PersonStore {
    private final Map<String, Person> people = new TreeMap<>();

    @Override
    public Optional<Person> find(String id) {
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

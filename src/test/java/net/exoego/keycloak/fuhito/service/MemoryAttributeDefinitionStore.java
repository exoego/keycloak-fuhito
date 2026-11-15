package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;

final class MemoryAttributeDefinitionStore implements AttributeDefinitionStore {

    final List<AttributeDefinition> definitions = new ArrayList<>();

    @Override
    public List<AttributeDefinition> list(AttributeDefinition.Target target) {
        return definitions.stream()
                .filter(d -> target == null || d.target() == target)
                .sorted(Comparator.comparing(AttributeDefinition::target).thenComparing(AttributeDefinition::name))
                .toList();
    }

    @Override
    public Optional<AttributeDefinition> find(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    @Override
    public void insert(AttributeDefinition definition) {
        definitions.add(definition);
    }

    @Override
    public void update(AttributeDefinition definition) {
        definitions.replaceAll(d -> d.id().equals(definition.id()) ? definition : d);
    }

    @Override
    public void delete(String id) {
        definitions.removeIf(d -> d.id().equals(id));
    }
}

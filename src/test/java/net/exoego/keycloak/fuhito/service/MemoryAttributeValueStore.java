package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.store.AttributeValueStore;

final class MemoryAttributeValueStore implements AttributeValueStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    // A resource with dates has a row for each version.
    record Row(Target target, String resourceId, Map<String, Object> attributes) {
    }

    final List<Row> rows = new ArrayList<>();
    final Map<String, Long> versions = new HashMap<>();

    Row add(Target target, String resourceId, Map<String, Object> attributes) {
        Row row = new Row(target, resourceId, new LinkedHashMap<>(attributes));
        rows.add(row);
        versions.putIfAbsent(resourceId, 1L);
        return row;
    }

    private Stream<Row> of(Target target, Predicate<Row> picked) {
        return rows.stream().filter(row -> row.target() == target).filter(picked);
    }

    private static Predicate<Row> missing(String name) {
        return row -> row.attributes().get(name) == null;
    }

    private static Predicate<Row> present(String name) {
        return row -> row.attributes().containsKey(name);
    }

    @Override
    public long countMissing(Target target, String name) {
        return of(target, missing(name)).count();
    }

    @Override
    public long countPresent(Target target, String name) {
        return of(target, present(name)).count();
    }

    @Override
    public long largestAfterFill(Target target, String name, Object value) {
        return of(target, missing(name)).mapToLong(row -> {
            Map<String, Object> filled = new LinkedHashMap<>(row.attributes());
            filled.put(name, value);
            try {
                return JSON.writeValueAsBytes(filled).length;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).max().orElse(0);
    }

    @Override
    public List<String> fill(Target target, String name, Object value) {
        List<Row> picked = of(target, missing(name)).toList();
        picked.forEach(row -> row.attributes().put(name, value));
        return countUp(picked);
    }

    @Override
    public List<String> clear(Target target, String name) {
        List<Row> picked = of(target, present(name)).toList();
        picked.forEach(row -> row.attributes().remove(name));
        return countUp(picked);
    }

    private List<String> countUp(List<Row> picked) {
        List<String> ids = picked.stream().map(Row::resourceId).distinct().sorted().toList();
        ids.forEach(id -> versions.merge(id, 1L, Long::sum));
        return ids;
    }
}

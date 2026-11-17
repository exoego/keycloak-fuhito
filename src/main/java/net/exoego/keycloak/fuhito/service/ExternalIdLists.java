package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.ExternalId;

/**
 * The IDs that systems have for many resources of one kind, read at once.
 */
public final class ExternalIdLists {

    /**
     * The lists of resources that have no IDs.
     */
    public static final ExternalIdLists NONE = new ExternalIdLists(List.of());

    private final Map<String, List<ExternalId>> byResource;

    ExternalIdLists(Collection<ExternalId> rows) {
        this.byResource = rows.stream().collect(Collectors.groupingBy(ExternalId::resourceId));
    }

    /**
     * Returns the IDs of one resource. The ID of the system that made it comes first, then the others by system.
     *
     * @param resourceId the ID of the resource
     * @param origin the name of the system that made the resource, or null
     * @return the IDs, which are empty for a resource that was not read
     */
    public List<ExternalId> of(String resourceId, String origin) {
        return ExternalId.ordered(byResource.getOrDefault(resourceId, List.of()), origin);
    }

    /**
     * Returns the ID that one system has for a resource.
     *
     * @param resourceId the ID of the resource
     * @param source the name of the system, or null
     * @return the ID, or null when the system has none for the resource
     */
    public String externalIdOf(String resourceId, String source) {
        return byResource.getOrDefault(resourceId, List.of()).stream()
                .filter(row -> row.source().equals(source))
                .map(ExternalId::externalId)
                .findFirst()
                .orElse(null);
    }
}

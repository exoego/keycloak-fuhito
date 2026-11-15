package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;

/**
 * Removes an org unit, an employment, or an assignment that was made by mistake. It works only when nothing
 * refers to the resource.
 */
public class RemovalService {

    private final ResourceRemoval removal;

    /**
     * Creates the service.
     *
     * @param removal the removal of the realm
     */
    public RemovalService(ResourceRemoval removal) {
        this.removal = removal;
    }

    /**
     * Removes a resource and every period of it.
     *
     * @param kind the kind of resource
     * @param id the ID
     * @throws FuhitoException when the resource does not exist, or other data refers to it
     */
    public void remove(ResourceRemoval.Kind kind, String id) {
        String prefix = switch (kind) {
            case ORG_UNIT -> "org_unit";
            case EMPLOYMENT -> "employment";
            case ASSIGNMENT -> "assignment";
        };
        if (!removal.exists(kind, id)) {
            throw FuhitoException.notFound(prefix + ".not_found", Map.of("id", id));
        }
        List<String> dependents = removal.dependents(kind, id);
        if (!dependents.isEmpty()) {
            throw FuhitoException.conflict(prefix + ".in_use", Map.of("id", id, "uses",
                    String.join(", ", dependents)));
        }
        removal.remove(kind, id);
    }
}

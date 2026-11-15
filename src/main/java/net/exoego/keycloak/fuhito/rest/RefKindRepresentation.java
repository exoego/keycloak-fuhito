package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import net.exoego.keycloak.fuhito.service.RealmKindService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A kind of reference data in requests and responses.
 */
@Schema(name = "RefKindDefinition", description = "A kind of reference data.", requiredProperties = {"code", "name"})
public record RefKindRepresentation(
        @Schema(description = "The code that items name as their kind.", maxLength = 32,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The default name. A fixed kind has none.", maxLength = 255)
        String name,
        @Schema(description = "Names in other languages, by language tag.")
        Map<String, String> names,
        @Schema(description = "True for a kind that every realm has.", readOnly = true)
        Boolean fixed) {

    static RefKindRepresentation of(RealmKindService.View view) {
        return new RefKindRepresentation(view.code(), view.name(), view.names().isEmpty() ? null : view.names(),
                view.fixed());
    }
}

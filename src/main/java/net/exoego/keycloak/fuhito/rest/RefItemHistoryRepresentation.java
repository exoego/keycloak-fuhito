package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.RefItemService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "RefItemHistory",
        description = "Every period of the names of a reference item. The other values of the item have no dates.",
        requiredProperties = {"id", "version", "kind", "code", "versions"})
public record RefItemHistoryRepresentation(
        String id,
        @Schema(description = "The version of the item. A write of names counts it up too.", readOnly = true)
        Long version,
        String kind,
        String code,
        @Schema(description = "The names of each period, oldest first.")
        List<Names> versions) {

    @Schema(
            name = "RefItemNames",
            description = "The names of a reference item for a period.",
            requiredProperties = {"name", "names"})
    public record Names(
            @Schema(description = "The first date. It is missing for names that hold from the start.", format = "date")
            String validFrom,
            @Schema(description = "The first date after the period. It is missing for no end.", format = "date")
            String validTo,
            @Schema(description = "The default name.")
            String name,
            @Schema(description = "Names in other languages, by language tag.")
            Map<String, String> names) {
    }

    static RefItemHistoryRepresentation of(RefItemService.History history) {
        var item = history.item();
        return new RefItemHistoryRepresentation(item.id(), item.version(), item.kind(), item.code(),
                history.names().stream()
                        .map(period -> new Names(Objects.toString(period.validFrom(), null),
                                Objects.toString(period.validTo(), null), period.name(), period.names()))
                        .toList());
    }
}

package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.RefItemName;
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
            requiredProperties = {"name", "primaryLanguage", "names"})
    public record Names(
            @Schema(description = "The first date. It is missing for names that hold from the start.", format = "date")
            String validFrom,
            @Schema(description = "The first date after the period. It is missing for no end.", format = "date")
            String validTo,
            @Schema(description = "The name in the language of the request, as `GET /ref-items/{id}` gives it.")
            String name,
            @Schema(description = "The language tag of the primary name. It is a key of `names`.")
            String primaryLanguage,
            @Schema(description = "The names by language tag.")
            Map<String, RefItemName> names) {
    }

    static RefItemHistoryRepresentation of(RefItemService.History history, Locale language) {
        var item = history.item();
        return new RefItemHistoryRepresentation(item.id(), item.version(), item.kind(), item.code(),
                history.names().stream()
                        .map(period -> new Names(Objects.toString(period.validFrom(), null),
                                Objects.toString(period.validTo(), null), period.names().in(language).name(),
                                period.names().primaryLanguage(), period.names().byLanguage()))
                        .toList());
    }
}

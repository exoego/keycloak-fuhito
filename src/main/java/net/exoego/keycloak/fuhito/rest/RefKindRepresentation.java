package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.RealmKindService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A kind of reference data in requests and responses.
 */
@Schema(name = "RefKindDefinition", description = "A kind of reference data.",
        requiredProperties = {"code", "primaryLanguage", "names"})
public record RefKindRepresentation(
        @Schema(description = "The ID of a kind that the realm added. A fixed kind has none.", readOnly = true)
        String id,
        @Schema(
                description = "The code that items name as their kind. It never changes. A write of a stored kind "
                        + "with another code fails with 400 and `invalid.value_fixed`.",
                maxLength = 32,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(
                description = "The name in the language of the request. It is the one of the first language of "
                        + "`Accept-Language`, else the one of that language with no region, such as `en` for "
                        + "`en-US`, else the primary one. A request with no `Accept-Language` reads the primary one. "
                        + "A fixed kind has none.",
                readOnly = true)
        String name,
        @Schema(
                description = "The language tag of the primary name, such as `ja`. It matches a key of `names` in "
                        + "standard form. A fixed kind has none.",
                maxLength = 64)
        String primaryLanguage,
        @Schema(
                description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. A read gives the tags in "
                        + "their standard form, such as `en-US` for `en-us`. A fixed kind has none. Two keys of one "
                        + "tag in standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
        Map<String, RefKindName> names,
        @Schema(description = "True for a kind that every realm has.", readOnly = true)
        Boolean fixed,
        @Schema(description = "The version of a kind that the realm added. A new kind has 1. Each write counts it "
                + "up by one, also a change of an ID of another system. A fixed kind has none. A write of a stored "
                + "kind sends the version that the caller read. A create needs none.")
        Long version,
        @Schema(
                description = "The ID of the kind in the system that `source` names. Only a create reads it. "
                        + "A read gives every ID in `externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. Only a create reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(description = "Every ID that a system has for the kind, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    static RefKindRepresentation of(RealmKindService.View view, ExternalIdLists ids, Locale language) {
        if (view.names() == null) {
            return new RefKindRepresentation(null, view.code(), null, null, null, view.fixed(), view.version(), null,
                    null, null);
        }
        return new RefKindRepresentation(view.id(), view.code(), view.names().in(language).name(),
                view.names().primaryLanguage(), view.names().byLanguage(), view.fixed(), view.version(), null, null,
                ExternalIdPairRepresentation.of(ids.of(view.id(), null)));
    }
}

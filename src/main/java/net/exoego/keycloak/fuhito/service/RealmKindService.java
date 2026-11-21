package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;
import net.exoego.keycloak.fuhito.store.RealmKindStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the kinds of reference data of one realm. The fixed kinds are there in every realm. A realm can
 * add kinds of its own.
 */
public class RealmKindService {

    private static final int MAX_CODE_LENGTH = 32;

    /**
     * A kind of reference data.
     *
     * @param id the ID, or null for a fixed kind
     * @param code the code that items name as their kind
     * @param names the names in each language, or null for a fixed kind
     * @param fixed true for a kind that every realm has
     * @param version the number that each write of the kind counts up, or null for a fixed kind, which never
     *        changes
     */
    public record View(String id, String code, Localized<RefKindName> names, boolean fixed, Long version) {

        static View of(RealmKind kind) {
            return new View(kind.id(), kind.code(), kind.names(), false, kind.version());
        }
    }

    private final RealmKindStore store;
    private final RefItemStore items;
    private final AttributeDefinitionStore definitions;

    /**
     * Creates the service.
     *
     * @param store the storage of the kinds of the realm
     * @param items the storage of reference items of the realm
     * @param definitions the storage of the definitions of free attributes of the realm
     */
    public RealmKindService(RealmKindStore store, RefItemStore items, AttributeDefinitionStore definitions) {
        this.store = store;
        this.items = items;
        this.definitions = definitions;
    }

    /**
     * Lists every kind, the fixed ones first, then the ones of the realm by code.
     *
     * @return the kinds
     */
    public List<View> list() {
        List<View> kinds = new ArrayList<>(Arrays.stream(RefKind.values())
                .map(kind -> new View(null, kind.name(), null, true, null))
                .toList());
        store.list().forEach(kind -> kinds.add(View.of(kind)));
        return kinds;
    }

    /**
     * Adds a kind to the realm.
     *
     * @param code the code
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @return the new kind
     * @throws FuhitoException when a value is bad, or the code is a fixed kind or taken
     */
    public View create(String code, String primaryLanguage, Map<String, RefKindName> names) {
        Checks.code("code", code);
        if (!Checks.fits(code, MAX_CODE_LENGTH)) {
            throw FuhitoException.invalid("invalid.code", Map.of("field", "code"));
        }
        requireNotFixed(code);
        if (store.findByCode(code).isPresent()) {
            throw FuhitoException.conflict("ref_kind.code_taken", Map.of("code", code));
        }
        RealmKind kind = new RealmKind(UUID.randomUUID().toString(), code,
                RefKindName.check(primaryLanguage, names), 1);
        store.insert(kind);
        return View.of(kind);
    }

    /**
     * Requires that a code is not the code of a fixed kind in any case, such as {@code grade}.
     *
     * @param code the code of a kind that a realm adds
     * @throws FuhitoException when it is
     */
    static void requireNotFixed(String code) {
        if (Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code.toUpperCase(Locale.ROOT)))) {
            throw FuhitoException.conflict("ref_kind.code_taken", Map.of("code", code));
        }
    }

    /**
     * Finds a kind that the realm added.
     *
     * @param code the code
     * @return the kind, or empty for a fixed kind or an unknown code
     */
    public Optional<View> find(String code) {
        return store.findByCode(code).map(View::of);
    }

    /**
     * Finds the code of a kind that the realm added, by its ID. It reads only the code, so a lock of the kind can
     * come after it.
     *
     * @param id the ID
     * @return the code, or empty when the realm added no kind with the ID
     */
    public Optional<String> codeOf(String id) {
        return store.codeOf(id);
    }

    /**
     * Returns a kind that the realm added.
     *
     * @param code the code
     * @return the kind
     * @throws FuhitoException when the realm added no such kind
     */
    public View get(String code) {
        return find(code).orElseThrow(() -> FuhitoException.notFound("ref_kind.not_found", Map.of("code", code)));
    }

    /**
     * Changes the names of a kind that the realm added. The code never changes.
     *
     * @param code the code
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @return the changed kind
     * @throws FuhitoException when a name is bad, or the realm added no such kind
     */
    public View rename(String code, String primaryLanguage, Map<String, RefKindName> names) {
        RealmKind kind = store.findByCode(code)
                .orElseThrow(() -> FuhitoException.notFound("ref_kind.not_found", Map.of("code", code)));
        store.update(new RealmKind(kind.id(), code, RefKindName.check(primaryLanguage, names), kind.version()));
        return get(code);
    }

    /**
     * Removes a kind of the realm that has no items and that no definition of a free attribute has.
     *
     * @param code the code
     * @throws FuhitoException when the realm added no such kind, the kind has items, or a definition has it
     */
    public void delete(String code) {
        RealmKind kind = store.findByCode(code)
                .orElseThrow(() -> FuhitoException.notFound("ref_kind.not_found", Map.of("code", code)));
        if (!items.list(code, true).isEmpty()) {
            throw FuhitoException.conflict("ref_kind.in_use", Map.of("code", code));
        }
        definitions.list(null).stream()
                .filter(definition -> code.equals(definition.refKind()))
                .findFirst()
                .ifPresent(definition -> {
                    throw FuhitoException.conflict("ref_kind.in_use", Map.of("code", code,
                            "target", definition.target().name(), "name", definition.name()));
                });
        store.delete(kind.id());
    }

    /**
     * Tells whether a code names a fixed kind or a kind of the realm.
     *
     * @param code the code
     * @return true when items can have it as their kind
     */
    public boolean isKnown(String code) {
        return Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code))
                || store.findByCode(code).isPresent();
    }
}

package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefKind;
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
     * @param code the code that items name as their kind
     * @param name the default name, or null for a fixed kind
     * @param names the names in other languages
     * @param fixed true for a kind that every realm has
     */
    public record View(String code, String name, Map<String, String> names, boolean fixed) {
    }

    private final RealmKindStore store;
    private final RefItemStore items;

    /**
     * Creates the service.
     *
     * @param store the storage of the kinds of the realm
     * @param items the storage of reference items of the realm
     */
    public RealmKindService(RealmKindStore store, RefItemStore items) {
        this.store = store;
        this.items = items;
    }

    /**
     * Lists every kind, the fixed ones first, then the ones of the realm by code.
     *
     * @return the kinds
     */
    public List<View> list() {
        List<View> kinds = new ArrayList<>(Arrays.stream(RefKind.values())
                .map(kind -> new View(kind.name(), null, Map.of(), true))
                .toList());
        store.list().forEach(kind -> kinds.add(new View(kind.code(), kind.name(), kind.names(), false)));
        return kinds;
    }

    /**
     * Adds a kind to the realm.
     *
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, or null
     * @return the new kind
     * @throws FuhitoException when a value is bad, or the code is a fixed kind or taken
     */
    public View create(String code, String name, Map<String, String> names) {
        Checks.code("code", code);
        if (code.length() > MAX_CODE_LENGTH) {
            throw FuhitoException.invalid("invalid.code", Map.of("field", "code"));
        }
        boolean fixed = Arrays.stream(RefKind.values())
                .anyMatch(kind -> kind.name().equals(code.toUpperCase(Locale.ROOT)));
        if (fixed || store.findByCode(code).isPresent()) {
            throw FuhitoException.conflict("ref_kind.code_taken", Map.of("code", code));
        }
        RealmKind kind = new RealmKind(UUID.randomUUID().toString(), code, Checks.name("name", name),
                Checks.localizedNames("names", names));
        store.insert(kind);
        return new View(kind.code(), kind.name(), kind.names(), false);
    }

    /**
     * Removes a kind of the realm that has no items.
     *
     * @param code the code
     * @throws FuhitoException when the realm added no such kind, or the kind has items
     */
    public void delete(String code) {
        RealmKind kind = store.findByCode(code)
                .orElseThrow(() -> FuhitoException.notFound("ref_kind.not_found", Map.of("code", code)));
        if (!items.list(code, true).isEmpty()) {
            throw FuhitoException.conflict("ref_kind.in_use", Map.of("code", code));
        }
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

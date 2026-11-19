package net.exoego.keycloak.fuhito.domain;

/**
 * A kind of reference data that a realm added, for example "working time system" or "collective agreement".
 *
 * @param id the ID
 * @param code the code, which items of the kind name as their kind
 * @param names the names in each language
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record RealmKind(String id, String code, Localized<RefKindName> names, long version) {
}

package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the people of one realm.
 */
public class PersonService {


    /**
     * The person of a user, and whether this call created it.
     *
     * @param person the person
     * @param created the new person, or null when the person was there already
     */
    public record Resolved(Person person, PersonView created) {
    }

    private final PersonStore store;
    private final ExternalIdWrites externalIds;
    private final UserDirectory users;
    private final Predicate<String> inUse;
    private final AttributeRules attributeRules;

    /**
     * Creates the service.
     *
     * @param store the storage of people of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param users the users of the realm
     * @param inUse tells whether an employment, a qualification, or a reporting line names the person with an ID
     */
    public PersonService(PersonStore store, ExternalIdStore externalIds, UserDirectory users,
            Predicate<String> inUse) {
        this(store, externalIds, users, inUse, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of people of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param users the users of the realm
     * @param inUse tells whether an employment, a qualification, or a reporting line names the person with an ID
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public PersonService(PersonStore store, ExternalIdStore externalIds, UserDirectory users,
            Predicate<String> inUse, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.store = store;
        this.externalIds = new ExternalIdWrites(externalIds, ExternalId.Kind.PERSON);
        this.users = users;
        this.inUse = inUse;
    }

    /**
     * Returns the person that is linked to a Keycloak user. Creates one when the user has none yet.
     * A write that names a user by ID uses this.
     *
     * @param userId the ID of the user
     * @return the person
     * @throws FuhitoException when no person is linked to the user and the user does not exist
     */
    public Resolved forUser(String userId) {
        Checks.required("userId", userId);
        Optional<Person> linked = store.findByUser(userId);
        if (linked.isPresent()) {
            return new Resolved(linked.get(), null);
        }
        PersonView created = create(new PersonValues(userId, null, null, null, null, null));
        return new Resolved(created.person(), created);
    }

    /**
     * Finds the person that is linked to a Keycloak user.
     *
     * @param userId the ID of the user
     * @return the person, or empty when no person is linked to the user
     */
    public Optional<Person> ofUser(String userId) {
        return store.findByUser(userId);
    }

    /**
     * Finds a person by ID.
     *
     * @param id the ID of the person
     * @return the person, or empty when the realm has none with the ID
     */
    public Optional<Person> find(String id) {
        return store.find(id);
    }

    /**
     * Creates a person.
     *
     * @param request the values of the request. The person keeps the source as the system that made it.
     * @return the new person
     * @throws FuhitoException when a value is bad, or the user or the external ID belongs to another person
     */
    public PersonView create(PersonValues request) {
        Checks.required("body", request);
        ExternalId.Pair externalId = externalIds.check(null, request.source(), request.externalId());
        Person person = checked(UUID.randomUUID().toString(), request,
                Checks.optionalCode("source", request.source()), 1);
        store.insert(person);
        externalIds.store(person.id(), externalId);
        return view(person);
    }

    /**
     * Replaces the names, the attributes, and the link to a Keycloak user. An external ID of the request is stored
     * as the ID that the system of its source has for the person. The person keeps the system that made it.
     *
     * @param id the ID
     * @param request the new values
     * @return the changed person
     * @throws FuhitoException when the person does not exist, a value is bad, or the external ID belongs to another
     *         person
     */
    public PersonView replace(String id, PersonValues request) {
        Checks.required("body", request);
        Person current = get(id).person();
        ExternalId.Pair externalId = externalIds.check(id, request.source(), request.externalId());
        store.update(checked(id, request, current.source(), current.version()));
        externalIds.store(id, externalId);
        return get(id);
    }

    /**
     * Returns a person.
     *
     * @param id the ID
     * @return the person
     * @throws FuhitoException when the realm has no such person
     */
    public PersonView get(String id) {
        return store.find(id).map(this::view)
                .orElseThrow(() -> FuhitoException.notFound("person.not_found", Map.of("id", id)));
    }

    /**
     * Deletes a person.
     *
     * @param id the ID
     * @throws FuhitoException when the person does not exist or something still names the person
     */
    public void delete(String id) {
        get(id);
        if (inUse.test(id)) {
            throw FuhitoException.conflict("person.in_use", Map.of("id", id));
        }
        store.delete(id);
    }

    /**
     * Lists the people of the realm, ordered by ID.
     *
     * @param userId the ID of a Keycloak user, to return only the person linked to it, or null for everyone
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @return the people
     */
    public List<PersonView> list(String userId, Integer first, Integer max) {
        return list(userId, first, max, null);
    }

    /**
     * Lists the people of the realm, ordered by ID, from a cursor on.
     *
     * @param userId the ID of a Keycloak user, to return only the person linked to it, or null for everyone
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @param after the cursor of the page before, or null to start at the first person
     * @return the people
     * @throws FuhitoException when the cursor is bad, or the request has a cursor and a number to skip
     */
    public List<PersonView> list(String userId, Integer first, Integer max, String after) {
        return list(userId, null, null, first, max, after);
    }

    /**
     * Finds people of the realm, ordered by ID, from a cursor on.
     *
     * @param userId the ID of a Keycloak user, to return only the person linked to it, or null for everyone
     * @param text a text that a name of the person or of its Keycloak user has, in any case, or null for any
     * @param staffNumber a staff number that an employment of the person has, or null for any
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @param after the cursor of the page before, or null to start at the first person
     * @return the people
     * @throws FuhitoException when the cursor is bad, or the request has a cursor and a number to skip
     */
    public List<PersonView> list(String userId, String text, String staffNumber, Integer first, Integer max,
            String after) {
        if (userId != null) {
            return store.findByUser(userId).map(this::view).stream().toList();
        }
        Cursor.requireAlone(after, first);
        Page page = Page.of(first, max);
        String afterId = after == null ? null : Cursor.decode(Cursor.PEOPLE, after).getFirst();
        String search = stripped(text);
        String number = stripped(staffNumber);
        List<Person> people = search == null && number == null
                ? store.list(afterId, page.first(), page.max())
                : store.search(search, number, afterId, page.first(), page.max());
        return people.stream().map(this::view).toList();
    }

    /**
     * Counts the people that {@link #list(String, String, String, Integer, Integer, String)} finds on all pages
     * together.
     *
     * @param userId the ID of a Keycloak user, to count only the person linked to it, or null for everyone
     * @param text a text that a name of the person or of its Keycloak user has, in any case, or null for any
     * @param staffNumber a staff number that an employment of the person has, or null for any
     * @return the number of people
     */
    public long count(String userId, String text, String staffNumber) {
        if (userId != null) {
            return store.findByUser(userId).isPresent() ? 1 : 0;
        }
        return store.count(stripped(text), stripped(staffNumber));
    }

    private static String stripped(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Person checked(String id, PersonValues request, String source, long version) {
        Localized<PersonName> names = PersonName.check(request.primaryLanguage(), request.names());
        String userId = request.userId();
        if (userId == null && names == null) {
            throw FuhitoException.invalid("person.name_required", Map.of());
        }
        if (userId != null) {
            if (!users.exists(userId)) {
                throw FuhitoException.invalid("person.user_unknown", Map.of("userId", userId));
            }
            Optional<Person> linked = store.findByUser(userId).filter(other -> !other.id().equals(id));
            if (linked.isPresent()) {
                throw FuhitoException.conflict("person.user_taken",
                        Map.of("userId", userId, "personId", linked.get().id()));
            }
        }
        return new Person(id, userId, names,
                attributeRules.check(AttributeDefinition.Target.PERSON, request.attributes()), source, version);
    }

    private PersonView view(Person person) {
        if (person.names() != null || person.userId() == null) {
            return new PersonView(person, null);
        }
        return new PersonView(person, users.displayName(person.userId()).orElse(null));
    }
}

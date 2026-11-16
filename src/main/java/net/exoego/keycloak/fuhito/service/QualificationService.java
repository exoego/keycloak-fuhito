package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.QualificationStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the qualifications of one realm.
 */
public class QualificationService {

    private final QualificationStore store;
    private final RefItemStore refItems;
    private final PersonStore people;
    private final UserDirectory users;
    private final Clock clock;
    private final AttributeRules attributeRules;

    /**
     * Creates the service.
     *
     * @param store the storage of qualifications of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param users the users of the realm
     * @param clock the clock that decides what "today" is
     */
    public QualificationService(QualificationStore store, RefItemStore refItems, PersonStore people,
            UserDirectory users, Clock clock) {
        this(store, refItems, people, users, clock, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of qualifications of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param users the users of the realm
     * @param clock the clock that decides what "today" is
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public QualificationService(QualificationStore store, RefItemStore refItems, PersonStore people,
            UserDirectory users, Clock clock, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.people = people;
        this.store = store;
        this.refItems = refItems;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Adds a qualification of a person. A person can hold many, and the same kind more than once.
     *
     * @param request the values of the request
     * @return the new qualification
     * @throws FuhitoException when a value is bad or the person does not exist
     */
    public QualificationView add(QualificationValues request) {
        Checks.required("body", request);
        String personId = Checks.required("personId", request.personId());
        Person person = people.find(personId).orElseThrow(
                () -> FuhitoException.invalid("qualification.person_unknown", Map.of("personId", personId)));
        Qualification qualification = checked(UUID.randomUUID().toString(), person, request, null,
                request.externalId(), request.source());
        store.insert(qualification);
        return view(qualification);
    }

    /**
     * Replaces the kind, the number, the dates, and the attributes of a qualification.
     *
     * @param id the ID
     * @param request the new values
     * @return the changed qualification
     * @throws FuhitoException when the qualification does not exist or a value is bad
     */
    public QualificationView replace(String id, QualificationValues request) {
        Checks.required("body", request);
        Qualification current = get(id).qualification();
        Qualification changed = checked(id,
                new Person(current.personId(), current.userId(), null, Map.of(), null, Map.of(), null, null),
                request, current.kindId(), current.externalId(), current.source());
        store.update(changed);
        return view(changed);
    }

    /**
     * Returns a qualification.
     *
     * @param id the ID
     * @return the qualification
     * @throws FuhitoException when the realm has no such qualification
     */
    public QualificationView get(String id) {
        return store.find(id).map(this::view)
                .orElseThrow(() -> FuhitoException.notFound("qualification.not_found", Map.of("id", id)));
    }

    /**
     * Finds a qualification.
     *
     * @param id the ID
     * @return the qualification, or empty when the realm has no such qualification
     */
    public Optional<QualificationView> find(String id) {
        return store.find(id).map(this::view);
    }

    /**
     * Finds a qualification by the pair of source and external ID that it has.
     *
     * @param source the name of the system
     * @param externalId the ID in that system
     * @return the qualification, or empty when none has the pair
     */
    public Optional<QualificationView> ofExternalId(String source, String externalId) {
        return store.findByExternalId(source, externalId).map(this::view);
    }

    /**
     * Deletes a qualification.
     *
     * @param id the ID
     * @throws FuhitoException when the qualification does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    /**
     * Lists the qualifications of a user, oldest first.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today. It is ignored when {@code all} is true.
     * @param all true to return every qualification, expired ones and future ones included
     * @return the qualifications
     * @throws FuhitoException when the user does not exist
     */
    public List<QualificationView> ofUser(String userId, String asOf, boolean all) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : Checks.optionalDate("asOf", asOf);
        if (!users.isKnown(userId)) {
            throw FuhitoException.notFound("user.not_found", Map.of("userId", userId));
        }
        return store.findByUser(userId).stream()
                .filter(qualification -> all || qualification.period().contains(date))
                .map(this::view)
                .toList();
    }

    /**
     * Lists the qualifications of a person, oldest first.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today. It is ignored when {@code all} is true.
     * @param all true to return every qualification, expired ones and future ones included
     * @return the qualifications
     * @throws FuhitoException when the date is bad
     */
    public List<QualificationView> ofPerson(String personId, String asOf, boolean all) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : Checks.optionalDate("asOf", asOf);
        return store.findByPerson(personId).stream()
                .filter(qualification -> all || qualification.period().contains(date))
                .map(this::view)
                .toList();
    }

    private Qualification checked(String id, Person person, QualificationValues request, String currentKindId,
            String externalId, String source) {
        String kind = Checks.required("kind", request.kind());
        // A qualification may keep a kind that was deactivated after it got it.
        String kindId = refItems.findByCode(RefKind.QUALIFICATION, kind)
                .filter(item -> item.active() || item.id().equals(currentKindId))
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", RefKind.QUALIFICATION.name(), "code", kind)));
        ValidPeriod period = Checks.period(Checks.date("validFrom", request.validFrom()),
                Checks.optionalDate("validTo", request.validTo()));
        return new Qualification(id, person.id(), person.userId(), kindId, Checks.optionalName("number", request.number()), period,
                attributeRules.check(AttributeDefinition.Target.QUALIFICATION, request.attributes()), externalId,
                source);
    }

    /**
     * Lists a page of the qualifications of a kind or of a date, by ID.
     *
     * @param kind the code of the kind to keep, or null for every kind
     * @param asOf a date as {@code YYYY-MM-DD} on which the qualification holds, or null for every date
     * @param page the part of the list to return
     * @param after the cursor after which the page starts, or null
     * @return the qualifications of the page
     * @throws FuhitoException when a value is bad or the kind is unknown
     */
    public List<QualificationView> list(String kind, String asOf, Page page, String after) {
        String afterId = after == null ? null : Cursor.decode(Cursor.QUALIFICATIONS, after).getFirst();
        return store.search(search(kind, asOf), afterId, page.first(), page.max()).stream()
                .map(this::view)
                .toList();
    }

    /**
     * Counts the qualifications of a kind or of a date.
     *
     * @param kind the code of the kind to keep, or null for every kind
     * @param asOf a date as {@code YYYY-MM-DD} on which the qualification holds, or null for every date
     * @return the number of qualifications
     * @throws FuhitoException when a value is bad or the kind is unknown
     */
    public long count(String kind, String asOf) {
        return store.count(search(kind, asOf));
    }

    private QualificationStore.Search search(String kind, String asOf) {
        String kindId = kind == null ? null : refItems.findByCode(RefKind.QUALIFICATION, kind)
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", RefKind.QUALIFICATION.name(), "code", kind)));
        return new QualificationStore.Search(kindId, Checks.optionalDate("asOf", asOf));
    }

    private QualificationView view(Qualification qualification) {
        return new QualificationView(qualification,
                refItems.find(qualification.kindId()).map(RefItem::code).orElse(null));
    }
}

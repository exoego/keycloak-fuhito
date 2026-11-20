package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.QualificationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final MemoryQualificationStore store = new MemoryQualificationStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final QualificationService service = new QualificationService(store, externalIds, refItems,
            MemoryPersonStore.linked(Set.of("doctor", "nurse")), Set.of("doctor", "nurse")::contains,
            Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo")));

    @BeforeEach
    void defineKinds() {
        for (String code : List.of("physician-license", "narcotics-license", "specialist")) {
            refItems.insert(new RefItem("kind-" + code, RefKind.QUALIFICATION, code, ItemNames.of(code), 0, Map.of(),
                    true));
        }
    }

    private static QualificationValues of(String userId, String kind, String number, String from, String to) {
        return new QualificationValues(userId, kind, number, from, to, null, null, null);
    }

    private FuhitoException failure(QualificationValues request) {
        return assertThrows(FuhitoException.class, () -> service.add(request));
    }

    @Test
    void listsQualificationsByKindAndDate() {
        refItems.insert(new RefItem("license", RefKind.QUALIFICATION, "license", ItemNames.of("License"), 0, Map.of(),
                true));
        refItems.insert(new RefItem("course", RefKind.QUALIFICATION, "course", ItemNames.of("Course"), 0, Map.of(),
                true));
        service.add(of("doctor", "license", "1", "2010-05-01", null));
        service.add(of("nurse", "license", "2", "2020-05-01", "2025-05-01"));
        service.add(of("nurse", "course", "3", "2021-05-01", null));

        assertEquals(2, service.count("license", null));
        assertEquals(1, service.count("license", "2026-01-01"));
        assertEquals(List.of("course"), service.list(null, "2026-01-01", Page.of(null, null), null).stream()
                .map(QualificationView::kind).filter("course"::equals).toList());
        assertEquals(1, service.list(null, null, Page.of(0, 1), null).size());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.count("degree", null))
                .code());
    }

    @Test
    void addsAQualificationWithEveryValue() {
        QualificationView view = service.add(new QualificationValues("doctor", "narcotics-license", " 第12345号 ",
                "2024-04-01", "2027-01-01", Map.of("issuer", "Tokyo"), "Q-1", "hr"));

        Qualification q = view.qualification();
        assertEquals(36, q.id().length());
        assertEquals("doctor", q.userId());
        assertEquals("kind-narcotics-license", q.kindId());
        assertEquals("narcotics-license", view.kind());
        assertEquals("第12345号", q.number());
        assertEquals(new ValidPeriod(LocalDate.of(2024, 4, 1), LocalDate.of(2027, 1, 1)), q.period());
        assertEquals(Map.of("issuer", "Tokyo"), q.attributes());
        assertEquals(Optional.of(q.id()), externalIds.idOf(ExternalId.Kind.QUALIFICATION, "hr", "Q-1"));
        assertEquals("hr", q.source());
        assertEquals(view, service.get(q.id()));
    }

    @Test
    void aUserHoldsManyQualificationsAndTheSameKindTwice() {
        service.add(of("doctor", "physician-license", "123", "2010-05-01", null));
        service.add(of("doctor", "specialist", null, "2018-04-01", "2023-04-01"));
        service.add(of("doctor", "specialist", null, "2023-04-01", "2028-04-01"));
        service.add(of("doctor", "narcotics-license", null, "2027-01-01", null));
        service.add(of("nurse", "specialist", null, "2020-04-01", null));

        // Today is 2026-06-15 in Tokyo.
        assertEquals(List.of("physician-license", "specialist"), kinds(service.ofUser("doctor", null, false)));
        assertEquals(List.of("physician-license", "specialist"), kinds(service.ofUser("doctor", "2020-01-01", false)));
        assertEquals(List.of("physician-license", "specialist", "narcotics-license"),
                kinds(service.ofUser("doctor", "2027-06-01", false)));
        assertEquals(List.of("physician-license", "specialist", "specialist", "narcotics-license"),
                kinds(service.ofUser("doctor", null, true)));
        assertEquals("123", service.ofUser("doctor", null, false).getFirst().qualification().number());
        // The same list is read by person.
        assertEquals(service.ofUser("doctor", "2027-06-01", false), service.ofPerson("doctor", "2027-06-01", false));
        assertEquals(service.ofUser("doctor", null, true), service.ofPerson("doctor", null, true));
        assertEquals(service.ofUser("doctor", null, false), service.ofPerson("doctor", null, false));
    }

    @Test
    void aReadOfEveryQualificationTakesNoDate() {
        for (FuhitoException e : List.of(
                assertThrows(FuhitoException.class, () -> service.ofUser("doctor", "2020-01-01", true)),
                assertThrows(FuhitoException.class, () -> service.ofPerson("doctor", "2020-01-01", true)))) {
            assertEquals("invalid.not_together", e.code());
            assertEquals(Map.of("field", "asOf", "otherField", "all"), e.params());
        }
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.ofPerson("doctor", "2020-02-30", true)).code());
    }

    private static List<String> kinds(List<QualificationView> views) {
        return views.stream().map(QualificationView::kind).toList();
    }

    @Test
    void replacesEverythingButTheUserAndTheSourceAndAddsTheIdOfAnotherSystem() {
        String id = service.add(new QualificationValues("doctor", "specialist", "A-1", "2018-04-01", "2023-04-01",
                Map.of("k", "v"), "Q-1", "hr")).qualification().id();

        QualificationView changed = service.replace(id, new QualificationValues("nurse", "narcotics-license", null,
                "2019-04-01", null, null, "P-7", "payroll"));

        Qualification q = changed.qualification();
        assertEquals("doctor", q.userId());
        assertEquals("narcotics-license", changed.kind());
        assertNull(q.number());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2019, 4, 1)), q.period());
        assertEquals(Map.of(), q.attributes());
        assertEquals("hr", q.source());
        assertEquals(List.of("hr Q-1", "payroll P-7"), externalIds.ofResource(ExternalId.Kind.QUALIFICATION, id)
                .stream().map(row -> row.source() + " " + row.externalId()).toList());
        assertEquals(changed, service.get(id));
        // Another qualification cannot take an ID that names this one.
        String other = service.add(new QualificationValues("doctor", "specialist", null, "2018-04-01", null, null,
                null, null)).qualification().id();
        assertEquals("external_id.taken", assertThrows(FuhitoException.class, () -> service.replace(other,
                new QualificationValues(null, "specialist", null, "2018-04-01", null, null, "P-7", "payroll")))
                .code());
    }

    @Test
    void aQualificationMayKeepADeactivatedKindButNotTakeANewOne() {
        String id = service.add(of("doctor", "specialist", null, "2018-04-01", null)).qualification().id();
        refItems.update(new RefItem("kind-specialist", RefKind.QUALIFICATION, "specialist", ItemNames.of("x"), 0,
                Map.of(), false));

        assertEquals("specialist", service.replace(id, of(null, "specialist", "N", "2018-04-01", null)).kind());
        assertEquals("ref_item.unknown", failure(of("nurse", "specialist", null, "2020-04-01", null)).code());
        refItems.delete("kind-specialist");
        assertNull(service.get(id).kind());
    }

    @Test
    void deletesAQualification() {
        String id = service.add(of("doctor", "specialist", null, "2018-04-01", null)).qualification().id();

        service.delete(id);

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(id));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("qualification.not_found", e.code());
        assertEquals("qualification.not_found",
                assertThrows(FuhitoException.class, () -> service.delete(id)).code());
        assertEquals("qualification.not_found", assertThrows(FuhitoException.class,
                () -> service.replace(id, of(null, "specialist", null, "2018-04-01", null))).code());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.add(null)).code());
        assertEquals("invalid.required", failure(of(null, "specialist", null, "2018-04-01", null)).code());
        assertEquals("invalid.required", failure(of("doctor", null, null, "2018-04-01", null)).code());
        assertEquals("invalid.required", failure(of("doctor", "specialist", null, null, null)).code());
        assertEquals("invalid.period", failure(of("doctor", "specialist", null, "2018-04-01", "2018-04-01")).code());
        assertEquals("invalid.name", failure(of("doctor", "specialist", " ", "2018-04-01", null)).code());
        FuhitoException kind = failure(of("doctor", "nope", null, "2018-04-01", null));
        assertEquals("ref_item.unknown", kind.code());
        assertEquals(Map.of("kind", "QUALIFICATION", "code", "nope"), kind.params());
        FuhitoException user = failure(of("nobody", "specialist", null, "2018-04-01", null));
        assertEquals(FuhitoException.Reason.INVALID, user.reason());
        assertEquals("qualification.person_unknown", user.code());
        assertTrue(store.rows.isEmpty());
        String id = service.add(of("doctor", "specialist", null, "2018-04-01", null)).qualification().id();
        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.replace(id, null)).code());

        FuhitoException unknownUser = assertThrows(FuhitoException.class,
                () -> service.ofUser("nobody", null, false));
        assertEquals(FuhitoException.Reason.NOT_FOUND, unknownUser.reason());
        assertEquals("user.not_found", unknownUser.code());
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.ofUser("doctor", "x", false)).code());
    }
}

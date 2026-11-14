package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.UserDataMasker;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserMaskingServiceTest {

    // Today is 2026-06-15 in Tokyo.
    private Clock clock = Clock.fixed(Instant.parse("2026-06-15T03:00:00.123456Z"), ZoneId.of("Asia/Tokyo"));
    private final Map<String, Instant> marks = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final UserDataMasker masker = new UserDataMasker() {
        @Override
        public Optional<Instant> maskedAt(String userId) {
            return Optional.ofNullable(marks.get(userId));
        }

        @Override
        public void mask(String userId, Instant now) {
            calls.add(userId + " " + now);
            marks.putIfAbsent(userId, now);
        }
    };
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final EmploymentService employments = new EmploymentService(employmentStore, new MemoryAssignmentStore(),
            new MemoryRefItemStore(), MemoryPersonStore.anyUser(), new MemoryReportingLineStore());
    private final UserDirectory users = new UserDirectory() {
        @Override
        public boolean exists(String userId) {
            return userId.equals("still-here");
        }

        @Override
        public boolean isKnown(String userId) {
            return !userId.equals("nobody");
        }
    };

    private UserMaskingService service() {
        return new UserMaskingService(masker, employmentStore, users, clock, clock);
    }

    private void employ(String userId, String from, String to) {
        employments.start(new NewEmployment(userId, from, to, null, null, null, null, null, null, null, null));
    }

    @Test
    void masksAUserWhoLeftAndKeepsTheFirstTime() {
        employ("left", "2020-04-01", "2024-04-01");
        employ("left", "2025-04-01", "2026-06-15");

        Instant first = service().mask("left");

        assertEquals(Instant.parse("2026-06-15T03:00:00.123Z"), first);
        assertEquals(Optional.of(first), service().maskedAt("left"));
        assertEquals(Optional.empty(), service().maskedAt("other"));

        clock = Clock.offset(clock, Duration.ofDays(30));
        assertEquals(first, service().mask("left"));
        assertEquals(List.of("left " + first, "left " + first), calls);
    }

    @Test
    void aUserWithNoEmploymentCanBeMasked() {
        assertEquals(Instant.parse("2026-06-15T03:00:00.123Z"), service().mask("still-here"));
    }

    @Test
    void rejectsAUserWhoIsStillEmployedOrWillBe() {
        employ("open", "2020-04-01", null);
        employ("ends-tomorrow", "2020-04-01", "2026-06-16");
        employ("future", "2027-04-01", null);

        for (String userId : List.of("open", "ends-tomorrow", "future")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> service().mask(userId));
            assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
            assertEquals("user.still_employed", e.code());
            assertEquals(Map.of("userId", userId), e.params());
        }
        assertEquals(List.of(), calls);
    }

    @Test
    void rejectsAnUnknownUser() {
        FuhitoException e = assertThrows(FuhitoException.class, () -> service().mask("nobody"));

        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("user.not_found", e.code());
    }
}

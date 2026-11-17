package net.exoego.keycloak.fuhito.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalIdTest {

    private static ExternalId id(String source) {
        return new ExternalId("row-" + source, ExternalId.Kind.PERSON, "person", source, "ID-" + source);
    }

    private static List<String> sources(List<ExternalId> ids) {
        return ids.stream().map(ExternalId::source).toList();
    }

    @Test
    void putsTheIdOfTheSystemThatMadeTheResourceFirstAndTheOthersBySystem() {
        List<ExternalId> ids = List.of(id("time"), id("payroll"), id("hr"), id("budget"));

        assertEquals(List.of("payroll", "budget", "hr", "time"), sources(ExternalId.ordered(ids, "payroll")));
        // A resource that names no system, or a system with no ID left, has its IDs by system only.
        assertEquals(List.of("budget", "hr", "payroll", "time"), sources(ExternalId.ordered(ids, null)));
        assertEquals(List.of("budget", "hr", "payroll", "time"), sources(ExternalId.ordered(ids, "ledger")));
        assertEquals(List.of(), ExternalId.ordered(List.of(), "hr"));
    }
}

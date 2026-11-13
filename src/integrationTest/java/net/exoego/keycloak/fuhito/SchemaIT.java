package net.exoego.keycloak.fuhito;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaIT {

    @Test
    void liquibaseCreatesTheTable() throws Exception {
        assertEquals("1", KeycloakEnvironment.sql(
                "select count(*) from information_schema.tables where table_name = 'fuhito_hierarchy'"));
    }
}

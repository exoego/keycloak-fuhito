package net.exoego.keycloak.fuhito;

import java.io.File;
import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakStartIT {

    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version");
    private static final File PROVIDER_JAR = new File(System.getProperty("provider.jar"));

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak");

    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION)
                    .withNetwork(NETWORK)
                    .withProviderLibsFrom(List.of(PROVIDER_JAR))
                    .withEnv("KC_DB", "postgres")
                    .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                    .withEnv("KC_DB_USERNAME", "keycloak")
                    .withEnv("KC_DB_PASSWORD", "keycloak");

    @BeforeAll
    static void start() {
        POSTGRES.start();
        KEYCLOAK.start();
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @Test
    void liquibaseCreatesTheTable() throws Exception {
        assertEquals("1", query("select count(*) from information_schema.tables where table_name = 'fuhito_hierarchy'"));
    }

    private static String query(String sql) throws Exception {
        var result = POSTGRES.execInContainer("psql", "-U", "keycloak", "-d", "keycloak", "-tAc", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().trim();
    }
}

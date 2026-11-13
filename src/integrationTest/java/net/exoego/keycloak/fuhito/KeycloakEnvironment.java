package net.exoego.keycloak.fuhito;

import java.io.File;
import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One Keycloak with the provider jar and one PostgreSQL, shared by all integration tests of a run.
 * Testcontainers removes the containers when the JVM exits.
 */
final class KeycloakEnvironment {

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak");

    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version"))
                    .withNetwork(NETWORK)
                    .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                    .withEnv("KC_DB", "postgres")
                    .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                    .withEnv("KC_DB_USERNAME", "keycloak")
                    .withEnv("KC_DB_PASSWORD", "keycloak");

    static {
        POSTGRES.start();
        KEYCLOAK.start();
    }

    private KeycloakEnvironment() {
    }

    static String sql(String sql) throws Exception {
        var result = POSTGRES.execInContainer("psql", "-U", "keycloak", "-d", "keycloak", "-tAc", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().trim();
    }
}

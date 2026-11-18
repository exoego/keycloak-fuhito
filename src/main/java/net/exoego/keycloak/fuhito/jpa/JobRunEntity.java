package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The last run of a long job of one realm.
 */
@Entity
@Table(name = "FUHITO_JOB_RUN")
public class JobRunEntity {

    @Id
    @Column(name = "ID", length = 36)
    String id;

    @Column(name = "REALM_ID", nullable = false, length = 36)
    String realmId;

    @Column(name = "JOB", nullable = false, length = 32)
    String job;

    @Column(name = "STATE", nullable = false, length = 16)
    String state;

    @Column(name = "STARTED_AT", nullable = false)
    Instant startedAt;

    @Column(name = "ENDED_AT")
    Instant endedAt;

    @Column(name = "REPORT")
    String report;

    @Column(name = "ERROR_CODE", length = 128)
    String errorCode;

    @Column(name = "CHECKPOINT_SEQUENCE")
    Long checkpointSequence;

    @Column(name = "CHECKPOINT_SETTINGS", length = 255)
    String checkpointSettings;

    /**
     * Creates an empty entity for JPA.
     */
    public JobRunEntity() {
    }
}

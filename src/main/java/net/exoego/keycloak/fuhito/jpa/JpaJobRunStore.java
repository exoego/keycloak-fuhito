package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.JobRunStore.JobRun;
import net.exoego.keycloak.fuhito.store.JobRunStore;

/**
 * Stores the last run of each long job of one realm in the database of Keycloak.
 */
public class JpaJobRunStore implements JobRunStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the session
     * @param realmId the ID of the realm
     */
    public JpaJobRunStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    private Optional<JobRunEntity> entity(String job) {
        return em.createQuery("select r from JobRunEntity r where r.realmId = :realmId and r.job = :job",
                        JobRunEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("job", job)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<JobRun> last(String job) {
        return entity(job).map(r -> new JobRun(r.job, r.state, r.startedAt, r.endedAt, r.report, r.errorCode));
    }

    @Override
    public void started(String job, Instant at) {
        RowLocks.lockRealm(em, realmId, false);
        Optional<JobRunEntity> stored = entity(job);
        JobRunEntity run = stored.orElseGet(JobRunEntity::new);
        run.state = "RUNNING";
        run.startedAt = at;
        run.endedAt = null;
        run.report = null;
        run.errorCode = null;
        if (stored.isEmpty()) {
            run.id = UUID.randomUUID().toString();
            run.realmId = realmId;
            run.job = job;
            em.persist(run);
        }
        em.flush();
    }

    @Override
    public void ended(String job, Instant at, String state, String report, String errorCode) {
        RowLocks.lockRealm(em, realmId, false);
        entity(job).ifPresent(run -> {
            run.state = state;
            run.endedAt = at;
            run.report = report;
            run.errorCode = errorCode;
            em.flush();
        });
    }
}

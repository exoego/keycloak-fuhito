package net.exoego.keycloak.fuhito.store;

import java.util.function.Supplier;

/**
 * A write of many rows in one transaction, such as an import. The storage may keep what the transaction read and
 * wrote, and a read may cost more with each row that it keeps. So the write tells the storage when each of its rows
 * is done.
 */
public interface LongWrite {

    /**
     * A long write for storage that keeps nothing.
     */
    LongWrite NONE = new LongWrite() {
        @Override
        public <T> T run(Supplier<T> work) {
            return work.get();
        }

        @Override
        public void rowDone() {
            // Nothing is kept.
        }
    };

    /**
     * Runs the whole write.
     *
     * @param <T> the type of the result
     * @param work the write
     * @return the result of the write
     */
    <T> T run(Supplier<T> work);

    /**
     * Tells that a row of the write is done. The storage may let go of what it keeps for the row. The writes of the
     * row stay in the transaction.
     */
    void rowDone();
}

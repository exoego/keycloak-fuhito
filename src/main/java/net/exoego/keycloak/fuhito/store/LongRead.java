package net.exoego.keycloak.fuhito.store;

/**
 * A read of many pages in one transaction, such as a CSV export. The storage may keep what the transaction read, and
 * a read may cost more with each row that it keeps. So the read tells the storage when each of its pages is done.
 */
public interface LongRead {

    /**
     * A long read for storage that keeps nothing.
     */
    LongRead NONE = () -> {
        // Nothing is kept.
    };

    /**
     * Tells that a page of the read is done. The storage may let go of what it keeps for the page.
     */
    void pageDone();
}

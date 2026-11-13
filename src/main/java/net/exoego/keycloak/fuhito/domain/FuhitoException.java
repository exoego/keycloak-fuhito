package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * A request that cannot be done, with a stable code that clients can rely on.
 */
public class FuhitoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The broad reason, which decides the HTTP status.
     */
    public enum Reason {
        /** The request has a bad value. */
        INVALID,
        /** The target does not exist. */
        NOT_FOUND,
        /** The request conflicts with existing data. */
        CONFLICT
    }

    /**
     * The broad reason.
     */
    private final Reason reason;

    /**
     * The stable error code.
     */
    private final String code;

    private final transient Map<String, String> params;

    /**
     * Creates the exception.
     *
     * @param reason the broad reason
     * @param code the stable error code, which is also the key of the message
     * @param params the values that the message refers to
     */
    public FuhitoException(Reason reason, String code, Map<String, String> params) {
        super(code + " " + params);
        this.reason = reason;
        this.code = code;
        this.params = Map.copyOf(params);
    }

    /**
     * Creates an exception for a bad value.
     *
     * @param code the stable error code
     * @param params the values that the message refers to
     * @return the exception
     */
    public static FuhitoException invalid(String code, Map<String, String> params) {
        return new FuhitoException(Reason.INVALID, code, params);
    }

    /**
     * Creates an exception for a missing target.
     *
     * @param code the stable error code
     * @param params the values that the message refers to
     * @return the exception
     */
    public static FuhitoException notFound(String code, Map<String, String> params) {
        return new FuhitoException(Reason.NOT_FOUND, code, params);
    }

    /**
     * Creates an exception for a conflict with existing data.
     *
     * @param code the stable error code
     * @param params the values that the message refers to
     * @return the exception
     */
    public static FuhitoException conflict(String code, Map<String, String> params) {
        return new FuhitoException(Reason.CONFLICT, code, params);
    }

    /**
     * Returns the broad reason.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the stable error code.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * Returns the values that the message refers to.
     *
     * @return the values by name
     */
    public Map<String, String> params() {
        return params;
    }
}

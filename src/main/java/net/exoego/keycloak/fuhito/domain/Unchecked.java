package net.exoego.keycloak.fuhito.domain;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Runs input and output that cannot fail in practice, such as JSON in memory, with no checked exception.
 */
public final class Unchecked {

    /**
     * An action that the compiler believes can fail on input or output.
     *
     * @param <T> the type of the result
     */
    @FunctionalInterface
    public interface Io<T> {

        /**
         * Runs the action.
         *
         * @return the result
         * @throws IOException when input or output fails
         */
        T run() throws IOException;
    }

    private Unchecked() {
    }

    /**
     * Runs an action and turns its {@link IOException} into an unchecked one.
     *
     * @param action the action
     * @param <T> the type of the result
     * @return the result of the action
     * @throws UncheckedIOException when the action fails on input or output
     */
    public static <T> T io(Io<T> action) {
        try {
            return action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

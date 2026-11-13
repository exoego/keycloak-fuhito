package net.exoego.keycloak.fuhito.domain;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Turns an error code and its values into text for people.
 */
public final class Messages {

    static final String BUNDLE = "net.exoego.keycloak.fuhito.messages";

    // Without this, a language with no bundle would get the language of the server, not English.
    private static final ResourceBundle.Control NO_JVM_DEFAULT =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private Messages() {
    }

    /**
     * Returns the message of an error.
     *
     * @param code the error code
     * @param params the values that the message refers to as {@code {name}}
     * @param locale the language. English is used when no bundle exists for it.
     * @return the message, or the code when no message exists
     */
    public static String format(String code, Map<String, String> params, Locale locale) {
        String text;
        try {
            text = ResourceBundle.getBundle(BUNDLE, locale, Messages.class.getClassLoader(), NO_JVM_DEFAULT)
                    .getString(code);
        } catch (MissingResourceException e) {
            return code;
        }
        for (Map.Entry<String, String> param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", param.getValue());
        }
        return text;
    }
}

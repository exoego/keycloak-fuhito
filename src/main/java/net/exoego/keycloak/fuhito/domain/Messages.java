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

    private Messages() {
    }

    /**
     * Returns the message of an error.
     *
     * @param code the error code
     * @param params the values that the message refers to as {@code {name}}
     * @param locale the language to use when the bundle has it
     * @return the message, or the code when no message exists
     */
    public static String format(String code, Map<String, String> params, Locale locale) {
        String text;
        try {
            text = ResourceBundle.getBundle(BUNDLE, locale, Messages.class.getClassLoader()).getString(code);
        } catch (MissingResourceException e) {
            return code;
        }
        for (Map.Entry<String, String> param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", param.getValue());
        }
        return text;
    }
}

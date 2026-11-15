package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Unchecked;

/**
 * The limit on the size of the free attributes of one write.
 */
final class AttributeSize {

    /** The largest JSON of the attributes of one write, in bytes of UTF-8. */
    static final int MAX_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AttributeSize() {
    }

    static Map<String, Object> require(Map<String, Object> attributes) {
        int size = Unchecked.io(() -> JSON.writeValueAsBytes(attributes)).length;
        if (size > MAX_BYTES) {
            throw FuhitoException.invalid("attribute.too_large",
                    Map.of("size", Integer.toString(size), "max", Integer.toString(MAX_BYTES)));
        }
        return attributes;
    }
}

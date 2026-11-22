package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserNamesTest {

    // A model that answers the getters of some values and null for any other.
    private static <T> T model(Class<T> type, Map<String, String> values) {
        return type.cast(Proxy.newProxyInstance(UserNamesTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> values.get(args == null ? method.getName() : (String) args[0])));
    }

    @Test
    void theGivenNameComesFirstUnlessTheRealmSaysOtherwise() {
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", null));
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", "given-first"));
        assertEquals("Taro Yamada", UserNames.of("Taro", "Yamada", "something else"));
        assertEquals("山田 太郎", UserNames.of("太郎", "山田", "family-first"));
    }

    @Test
    void aMissingPartIsLeftOut() {
        assertEquals("Taro", UserNames.of(" Taro ", null, null));
        assertEquals("山田", UserNames.of("  ", "山田", "family-first"));
        assertEquals("", UserNames.of(null, null, null));
    }

    @Test
    void theNameOfAUserTakesTheOrderOfItsRealm() {
        RealmModel realm = model(RealmModel.class, Map.of(UserNames.ORDER_ATTRIBUTE, "family-first"));

        assertEquals("山田 太郎", UserNames.of(realm,
                model(UserModel.class, Map.of("getFirstName", "太郎", "getLastName", "山田"))));
        assertEquals("", UserNames.of(realm, null));
    }
}

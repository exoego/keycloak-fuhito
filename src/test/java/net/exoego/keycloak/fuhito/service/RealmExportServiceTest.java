package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.LongWrite;
import net.exoego.keycloak.fuhito.store.RealmExportStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealmExportServiceTest {

    // Each list reads its own page of the store. So the lists are every page that the export reads, and no other.
    @Test
    void theListsAreThePagesOfTheStoreThatTheExportReads() {
        Set<String> read = new HashSet<>();
        RealmExportStore store = (RealmExportStore) Proxy.newProxyInstance(
                RealmExportServiceTest.class.getClassLoader(), new Class<?>[] {RealmExportStore.class},
                (proxy, method, args) -> {
                    assertEquals(RealmExportStore.Page.class, method.getReturnType(), method.getName());
                    read.add(method.getName());
                    return (RealmExportStore.Page<?>) (afterId, max) -> List.of();
                });
        RealmExportService service = new RealmExportService(new RealmExportService.Stores(store, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null), LongWrite.NONE);

        for (String list : RealmExport.LISTS) {
            int before = read.size();
            assertEquals(List.of(), service.page(list, null, 10).rows(), list);
            assertEquals(before + 1, read.size(), list);
        }
        assertEquals(Arrays.stream(RealmExportStore.class.getMethods())
                .filter(method -> method.getReturnType() == RealmExportStore.Page.class)
                .map(Method::getName)
                .collect(Collectors.toSet()), read);
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.page("erasure", null, 10));
        assertEquals("export.list_unknown", e.code());
    }
}

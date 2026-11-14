package net.exoego.keycloak.fuhito;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules about which code may do what. They keep the places small where a mistake can open a hole.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.exoego.keycloak.fuhito");

    // A query is where an injection can happen. So every query is in one package that is easy to review.
    @Test
    void onlyTheJpaPackageTalksToTheEntityManager() {
        noClasses().that().resideOutsideOfPackage("..fuhito.jpa..")
                .should().callMethodWhere(describe("is a method of the entity manager",
                        call -> call.getTargetOwner().isAssignableTo(EntityManager.class)))
                .because("queries belong in the jpa package")
                .check(CLASSES);
    }

    // JPQL names entities and fields that the compiler and Hibernate know. Native SQL has no such check.
    @Test
    void noCodeUsesNativeSql() {
        noClasses()
                .should().callMethodWhere(describe("creates a native query",
                        call -> call.getTargetOwner().isAssignableTo(EntityManager.class)
                                && call.getName().equals("createNativeQuery")))
                .because("every query is JPQL or a criteria query")
                .check(CLASSES);
    }

    @Test
    void theDomainKnowsNoOtherLayer() {
        noClasses().that().resideInAPackage("..fuhito.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..fuhito.service..", "..fuhito.store..",
                        "..fuhito.jpa..", "..fuhito.rest..", "..fuhito.keycloak..", "org.keycloak..", "jakarta..")
                .check(CLASSES);
    }

    @Test
    void theServicesKnowNoStorageAndNoKeycloak() {
        noClasses().that().resideInAnyPackage("..fuhito.service..", "..fuhito.store..")
                .should().dependOnClassesThat().resideInAnyPackage("..fuhito.jpa..", "..fuhito.rest..",
                        "..fuhito.keycloak..", "org.keycloak..", "jakarta.persistence..")
                .check(CLASSES);
    }
}

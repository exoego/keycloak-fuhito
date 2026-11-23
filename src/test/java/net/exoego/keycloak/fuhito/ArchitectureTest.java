package net.exoego.keycloak.fuhito;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.jpa.JpaAttributeValues;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
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

    // JPQL names entities and fields that the compiler and Hibernate know. Native SQL has no such check. Only the fill
    // and the clear of the values of an attribute need it, as JPQL cannot change a value inside JSON text. So does the
    // lock of the cleanup of the change log, as JPA has no FOR KEY SHARE.
    @Test
    void noCodeUsesNativeSql() {
        noClasses().that().doNotHaveFullyQualifiedName(JpaAttributeValues.class.getName())
                .and().doNotHaveFullyQualifiedName(RowLocks.class.getName())
                .should().callMethodWhere(describe("creates a native query",
                        call -> call.getTargetOwner().isAssignableTo(EntityManager.class)
                                && call.getName().equals("createNativeQuery")))
                .because("every query is JPQL or a criteria query")
                .check(CLASSES);
    }

    // The rows of the periods of an assignment have no version. A read that made assignments of them alone would
    // give a wrong version with no error.
    @Test
    void onlyOneClassMakesAssignmentsOutOfStoredRows() {
        noClasses().that().resideInAPackage("..fuhito.jpa..")
                .and().doNotHaveFullyQualifiedName("net.exoego.keycloak.fuhito.jpa.AssignmentReads")
                .should().callConstructorWhere(describe("makes an assignment",
                        call -> call.getTargetOwner().isEquivalentTo(Assignment.class)))
                .because("AssignmentReads reads the versions of the assignments with their rows")
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

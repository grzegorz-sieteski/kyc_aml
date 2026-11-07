package com.gs5.kycaml;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The web edge talks to the domain and the access gate, and to no storage or messaging code. */
class RiskWebRulesTest {

    private static final com.tngtech.archunit.core.domain.JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.gs5.kycaml");

    @Test
    @DisplayName("web never depends on persistence, projection or events")
    void shouldKeepTheWebEdgeAwayFromStorage() {
        noClasses().that().resideInAPackage("..adapters.web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapters.persistence..", "..adapters.projection..", "..adapters.events..")
                .check(MAIN);
    }

    @Test
    @DisplayName("domain never depends on any adapter")
    void shouldKeepTheDomainFreeOfAdapters() {
        noClasses().that().resideInAPackage("..risk.domain..")
                .should().dependOnClassesThat().resideInAPackage("..risk.adapters..")
                .check(MAIN);
    }

    @Test
    @DisplayName("a class that handles HTTP is named *Resource, never *Controller")
    void shouldNameHttpClassesResource() {
        classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().haveSimpleNameEndingWith("Resource")
                .check(MAIN);
    }
}

package com.gs5.kycaml;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * The structural half of the architecture gate, for the code that exists before any business
 * module: the shared kernel and the rules that hold everywhere.
 *
 * <p>This covers what is visible in declared types and dependencies. It does <em>not</em> cover
 * statements: ArchUnit cannot see a {@code throw new IllegalArgumentException(...)}, because that
 * class belongs to the JDK and nobody declared it here. Checkstyle
 * ({@code config/checkstyle/checkstyle.xml}) closes that half. Neither gate alone is enough.
 *
 * <p>The module rules (domain purity, CQS at the use case, aggregate visibility, commands as
 * standalone records) arrive with the first module, written by {@code @test-designer} in the RED
 * stage. They are not here yet because {@code archRule.failOnEmptyShould=true} would fail a rule
 * that matches no class, and that is the behaviour we want to keep.
 */
@AnalyzeClasses(
        packages = "com.gs5.kycaml",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    // ---------------------------------------------------------------- purity

    @ArchTest
    static final ArchRule shared_kernel_stays_pure_java =
            classes()
                    .that().resideInAPackage("..shared.kernel..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "java..",
                            "com.gs5.kycaml.shared.kernel..",
                            "org.jspecify..")
                    .because("the shared kernel is read by every module's domain/, which may use plain "
                            + "Java, shared.kernel and JSpecify only; a framework type here would leak "
                            + "into every business core");

    @ArchTest
    static final ArchRule orm_and_query_builders_are_absent_everywhere =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "org.hibernate..",
                            "com.querydsl..",
                            "org.jooq..")
                    .because("persistence is plain Spring Data JDBC with native SQL mapped to records; "
                            + "models reach for @Entity and JPAQueryFactory on reflex, so block them outright");

    // ------------------------------------------------------- no hidden layers

    @ArchTest
    static final ArchRule no_procedural_service_or_facade_layer =
            noClasses()
                    .should().haveSimpleNameEndingWith("Service")
                    .orShould().haveSimpleNameEndingWith("Facade")
                    .because("entry points are single-operation *UseCase classes; a *Service or *Facade "
                            + "collects unrelated operations, every new feature edits the same file, and "
                            + "an anaemic model and a god object grow back");

    @ArchTest
    static final ArchRule no_grab_bag_classes =
            noClasses()
                    .should().haveSimpleNameEndingWith("Util")
                    .orShould().haveSimpleNameEndingWith("Utils")
                    .orShould().haveSimpleNameEndingWith("Helper")
                    .orShould().haveSimpleNameEndingWith("Manager")
                    .because("behaviour belongs on the type that owns the data");

    // ---------------------------------------------------- dependency injection

    @ArchTest
    static final ArchRule no_field_injection =
            noFields()
                    .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .orShould().beAnnotatedWith("jakarta.inject.Inject")
                    .because("dependencies must be injected through constructors; "
                            + "field injection hides dependencies and bypasses compile-time checks");

    // ------------------------------------------------------- failures as data

    @ArchTest
    static final ArchRule no_custom_throwables =
            noClasses()
                    .should().beAssignableTo(Throwable.class)
                    .because("business failures are values returned in Result.failure(...), "
                            + "not control flow. NOTE: this only catches declared exception types. "
                            + "`throw new IllegalArgumentException(...)` is invisible here and is "
                            + "caught by the Checkstyle noThrowNew rule instead");

    // --------------------------------------------------- virtual-thread safety

    @ArchTest
    static final ArchRule no_thread_local_on_virtual_threads =
            noClasses()
                    .should().dependOnClassesThat().areAssignableTo(ThreadLocal.class)
                    .because("the service runs on virtual threads; use ActorContext / ScopedValue");

    // ----------------------------------------------------------- messaging

    @ArchTest
    static final ArchRule kafka_is_reachable_only_from_the_outbound_adapter =
            noClasses()
                    .that().resideOutsideOfPackages("..adapters.messaging..", "..adapters.config..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because("publishing goes through the Transactional Outbox relay; a KafkaTemplate "
                            + "injected into business code is a dual write waiting to lose a message");
}

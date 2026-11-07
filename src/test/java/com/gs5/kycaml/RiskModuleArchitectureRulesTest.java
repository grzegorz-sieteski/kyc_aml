package com.gs5.kycaml;

import com.gs5.kycaml.risk.domain.RecordedFact;
import com.gs5.kycaml.risk.domain.RiskDomainEvent;
import com.gs5.kycaml.risk.domain.RiskProfileId;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * The structural rules of the {@code risk} module (design section 11). Checkstyle closes what
 * ArchUnit cannot see; this class closes what is visible in declared types and dependencies.
 *
 * <p>Every rule matches at least one class today, because {@code archRule.failOnEmptyShould} is true.
 * The aggregate rule matches nothing until the aggregate exists, and fails until then on purpose.
 */
@AnalyzeClasses(
        packages = "com.gs5.kycaml",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RiskModuleArchitectureRulesTest {

    private static final DescribedPredicate<JavaClass> COMMAND_OR_QUERY =
            simpleNameEndingWith("Command").or(simpleNameEndingWith("Query"));

    private static DescribedPredicate<JavaClass> exposingPublicMethodNamed(String methodName) {
        return new DescribedPredicate<>("exposing a public method named '" + methodName + "'") {
            @Override
            public boolean test(JavaClass type) {
                return type.getMethods().stream().anyMatch(method ->
                        method.getName().equals(methodName) && method.getModifiers().contains(JavaModifier.PUBLIC));
            }
        };
    }

    private static final DescribedPredicate<JavaClass> READ_USE_CASE =
            simpleNameEndingWith("UseCase").and(exposingPublicMethodNamed("ask"));

    private static final DescribedPredicate<JavaClass> WRITE_USE_CASE =
            simpleNameEndingWith("UseCase").and(exposingPublicMethodNamed("execute"));

    // ----------------------------------------------------------------------- purity

    @ArchTest
    static final ArchRule risk_domain_stays_pure_java =
            classes()
                    .that().resideInAPackage("..risk.domain..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "java..",
                            "com.gs5.kycaml.risk.domain..",
                            "com.gs5.kycaml.shared.kernel..",
                            "org.jspecify..")
                    .because("the business rules must be testable in milliseconds, without a container: "
                            + "plain Java, the shared kernel and JSpecify only");

    @ArchTest
    static final ArchRule the_domain_never_reads_the_clock_or_makes_randomness =
            noClasses()
                    .that().resideInAPackage("..risk.domain..")
                    .should().callMethodWhere(
                            target(name("now")).and(target(owner(assignableTo(Instant.class))))
                                    .or(target(name("randomUUID")).and(target(owner(assignableTo(UUID.class)))))
                                    .or(target(name("systemUTC")).and(target(owner(assignableTo(Clock.class))))))
                    .because("the write side is a pure function of its inputs: time comes from a Clock "
                            + "argument and ids from the RiskProfileIdGenerator port");

    // ----------------------------------------------------------- aggregate and values

    @ArchTest
    static final ArchRule the_aggregate_is_package_private =
            classes()
                    .that().haveSimpleName("CustomerRiskProfile")
                    .should().bePackagePrivate()
                    .because("only the use cases may reach the aggregate; the compiler then keeps adapters out");

    @ArchTest
    static final ArchRule guarded_types_have_no_public_constructor =
            constructors()
                    .that().areDeclaredInClassesThat(
                            simpleName("CustomerNumber").or(simpleName("Justification")).or(simpleName("CountryCode")))
                    .should().notBePublic()
                    .because("an invalid value must be unrepresentable: the only way in is parse");

    @ArchTest
    static final ArchRule raw_text_enters_only_through_parse =
            methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..risk.domain..")
                    .and().haveModifier(JavaModifier.PUBLIC)
                    .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
                    .and(new DescribedPredicate<JavaMethod>("taking a String parameter") {
                        @Override
                        public boolean test(JavaMethod method) {
                            return method.getRawParameterTypes().stream()
                                    .anyMatch(parameter -> parameter.isEquivalentTo(String.class));
                        }
                    })
                    .and(new DescribedPredicate<JavaMethod>("not the valueOf the compiler writes into an enum") {
                        @Override
                        public boolean test(JavaMethod method) {
                            return !(method.getOwner().isEnum() && method.getName().equals("valueOf"));
                        }
                    })
                    .should().haveName("parse")
                    .because("raw text enters the domain only as the parameter of a public method named parse; "
                            + "anywhere else a public String parameter is an unchecked value reaching business code. "
                            + "Scoped to public methods on purpose: the first version covered every method and "
                            + "failed on private helpers of a parse and on the aggregate's String actor id, which "
                            + "comes from the admitted ActorContext and is not request text (found at GREEN, "
                            + "fixed by the test designer)");

    // ------------------------------------------------------------- commands and queries

    @ArchTest
    static final ArchRule commands_and_queries_are_standalone_records =
            classes()
                    .that().resideInAPackage("..risk.domain..")
                    .and(COMMAND_OR_QUERY)
                    .should().beRecords()
                    .because("a command is an immutable bag of input, not a node in a command bus");

    @ArchTest
    static final ArchRule commands_and_queries_carry_only_parsed_values =
            noFields()
                    .that().areDeclaredInClassesThat(COMMAND_OR_QUERY)
                    .should().haveRawType(String.class)
                    .orShould().haveRawType(BigDecimal.class)
                    .because("a String or BigDecimal field is a value nobody has checked");

    // ------------------------------------------------------------------ CQS at the use case

    @ArchTest
    static final ArchRule use_cases_expose_exactly_one_public_method_named_ask_or_execute =
            classes()
                    .that().resideInAPackage("..risk.domain..")
                    .and().haveSimpleNameEndingWith("UseCase")
                    .should(exposeExactlyOnePublicMethodNamedAskOrExecute())
                    .because("one operation is one class with one public method; a second operation is "
                            + "a second class, never a second method");

    @ArchTest
    static final ArchRule read_use_cases_do_not_touch_the_write_side =
            noClasses()
                    .that(READ_USE_CASE)
                    .should().dependOnClassesThat(
                            simpleName("RiskProfileRepository")
                                    .or(simpleName("CustomerRiskProfile"))
                                    .or(simpleName("RiskProfileSnapshot"))
                                    .or(simpleName("BookingCentres"))
                                    .or(simpleName("RiskProfileIdGenerator"))
                                    .or(assignableTo(RecordedFact.class))
                                    .or(assignableTo(RiskDomainEvent.class)))
                    .because("a query reads from the query side and never loads an aggregate, saves, "
                            + "or announces anything");

    @ArchTest
    static final ArchRule write_use_cases_do_not_read_the_query_side =
            noClasses()
                    .that(WRITE_USE_CASE)
                    .should().dependOnClassesThat(
                            simpleName("ComplianceRiskViewReader")
                                    .or(simpleName("FrontOfficeStatusReader"))
                                    .or(simpleName("ComplianceRiskView"))
                                    .or(simpleName("FrontOfficeStatusView")))
                    .because("a command decides from the write side; the views are eventually consistent "
                            + "and a decision made on one would be a decision on stale data");

    // ------------------------------------------------------------ nothing internal leaves

    @ArchTest
    static final ArchRule events_and_views_never_carry_the_internal_identity =
            noFields()
                    .that().areDeclaredInClassesThat(assignableTo(RiskDomainEvent.class)
                            .or(simpleNameEndingWith("View")))
                    .should().haveRawType(RiskProfileId.class)
                    .because("the internal identity never leaves the service; a customer is named by the "
                            + "customer number alone");

    private static ArchCondition<JavaClass> exposeExactlyOnePublicMethodNamedAskOrExecute() {
        return new ArchCondition<>("expose exactly one public method, named ask or execute") {
            @Override
            public void check(JavaClass useCase, ConditionEvents events) {
                var publicMethods = useCase.getMethods().stream()
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .map(JavaMethod::getName)
                        .toList();
                var ok = publicMethods.size() == 1 && Set.of("ask", "execute").contains(publicMethods.getFirst());
                events.add(new SimpleConditionEvent(useCase, ok, useCase.getName()
                        + " exposes public methods " + publicMethods));
            }
        };
    }
}

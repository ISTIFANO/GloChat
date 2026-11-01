package com.marketchat.commerce;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DDD dependency rules (spec §1.8b). Rules are written now so every new class is checked from day one;
 * {@code allowEmptyShould(true)} lets them pass while a context has no classes yet.
 */
@AnalyzeClasses(packages = "com.marketchat.commerce", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotUseOuterLayers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotUseInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    /** A context may use another context only through its application package (public use cases). */
    @ArchTest
    static final ArchRule contextsTalkThroughApplicationOnly = classes()
            .that().resideInAPackage("com.marketchat.commerce..")
            .should(new ArchCondition<JavaClass>("only use other bounded contexts through their application package") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    String from = contextOf(origin.getPackageName());
                    if (from == null) {
                        return;
                    }
                    for (Dependency d : origin.getDirectDependenciesFromSelf()) {
                        String targetPkg = d.getTargetClass().getPackageName();
                        String to = contextOf(targetPkg);
                        if (to != null && !to.equals(from) && !targetPkg.startsWith(BASE + to + ".application")) {
                            events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                        }
                    }
                }
            })
            .allowEmptyShould(true);

    private static final String BASE = "com.marketchat.commerce.";
    private static final List<String> CONTEXTS = List.of("catalog", "cart", "checkout", "orders");

    private static String contextOf(String pkg) {
        for (String c : CONTEXTS) {
            if (pkg.equals(BASE + c) || pkg.startsWith(BASE + c + ".")) {
                return c;
            }
        }
        return null;
    }
}

package com.marketchat.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** DDD dependency rules for payment-service (spec §1.8b): Stripe and Spring stay in the infrastructure layer. */
@AnalyzeClasses(packages = "com.marketchat.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.stripe..", "tools.jackson..", "com.fasterxml..");

    @ArchTest
    static final ArchRule applicationKnowsNoFrameworkOrProvider = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "org.springframework..", "com.stripe..");
}

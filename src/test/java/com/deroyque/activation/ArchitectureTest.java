package com.deroyque.activation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Direção das dependências: api → application → domain. Infraestrutura implementa portas da aplicação.
 * O domínio não conhece web, HTTP nem as camadas acima.
 */
@AnalyzeClasses(packages = "com.deroyque.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.deroyque.activation.";

    @ArchTest
    static final ArchRule domainDependsOnNothingAbove = noClasses()
            .that().resideInAPackage(ROOT + "domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "application..", ROOT + "api..", ROOT + "infrastructure..", ROOT + "localdev..",
                    "org.springframework.web..", "org.springframework.http..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnWebOrInfrastructure = noClasses()
            .that().resideInAPackage(ROOT + "application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "api..", ROOT + "infrastructure..", ROOT + "localdev..",
                    "org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule apiDoesNotAccessRepositoriesDirectly = noClasses()
            .that().resideInAPackage(ROOT + "api..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructureDetails = noClasses()
            .that().resideInAPackage(ROOT + "api..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "infrastructure..", ROOT + "localdev..");

    @ArchTest
    static final ArchRule productionCodeDoesNotDependOnLocalDev = noClasses()
            .that().resideOutsideOfPackage(ROOT + "localdev..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "localdev..");
}

package com.deroyque.activation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.deroyque.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.deroyque.activation.";

    @ArchTest
    static final ArchRule controllerDoesNotAccessRepositoryOrIntegration = noClasses()
            .that().resideInAPackage(ROOT + "api..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "repository..", ROOT + "intercomm..");

    @ArchTest
    static final ArchRule jobDoesNotAccessRepositoryOrIntegration = noClasses()
            .that().resideInAPackage(ROOT + "job..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "repository..", ROOT + "intercomm..");

    @ArchTest
    static final ArchRule serviceDoesNotDependOnApi = noClasses()
            .that().resideInAPackage(ROOT + "service..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "api..", ROOT + "job..", "org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule repositoryIsALeaf = noClasses()
            .that().resideInAPackage(ROOT + "repository..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "service..", ROOT + "api..", ROOT + "intercomm..", ROOT + "job..");

    @ArchTest
    static final ArchRule intercommIsALeaf = noClasses()
            .that().resideInAPackage(ROOT + "intercomm..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "service..", ROOT + "api..", ROOT + "job..");

    @ArchTest
    static final ArchRule productionCodeDoesNotDependOnLocalDev = noClasses()
            .that().resideOutsideOfPackage(ROOT + "localdev..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "localdev..");

}

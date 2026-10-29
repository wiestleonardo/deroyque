package com.deroyque.ativacao;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.deroyque.ativacao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String RAIZ = "com.deroyque.ativacao.";

    @ArchTest
    static final ArchRule controllerNaoAcessaRepositoryNemIntegracao = noClasses()
            .that().resideInAPackage(RAIZ + "api..")
            .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "repository..", RAIZ + "intercomm..");

    @ArchTest
    static final ArchRule jobNaoAcessaRepository = noClasses()
            .that().resideInAPackage(RAIZ + "job..")
            .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "repository..", RAIZ + "intercomm..");

    @ArchTest
    static final ArchRule serviceNaoDependeDaApi = noClasses()
            .that().resideInAPackage(RAIZ + "service..")
            .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "api..", RAIZ + "job..", "org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule repositoryEhPontaFinal = noClasses()
            .that().resideInAPackage(RAIZ + "repository..")
            .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "service..", RAIZ + "api..", RAIZ + "intercomm..", RAIZ + "job..");

    @ArchTest
    static final ArchRule intercommEhPontaFinal = noClasses()
            .that().resideInAPackage(RAIZ + "intercomm..")
            .should().dependOnClassesThat().resideInAnyPackage(RAIZ + "service..", RAIZ + "api..", RAIZ + "job..");

    @ArchTest
    static final ArchRule codigoDeProducaoNaoDependeDoLocalDev = noClasses()
            .that().resideOutsideOfPackage(RAIZ + "localdev..")
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "localdev..");

}

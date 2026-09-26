package com.example.dpop.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec

/**
 * The account module's layers (docs/ideen/fachkern-und-technik-trennen.md): `domain` holds the rules
 * (anchor decision, claim normalization, spelling rule), `application` the services that apply them,
 * `infrastructure` the entities and repositories.
 */
class AccountArchitectureTest : BehaviorSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.dpop.account")

    given("the account module's domain") {
        then("it uses no framework") {
            noClasses()
                .that().resideInAPackage("com.example.dpop.account.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta..", "org.springframework..", "org.hibernate..", "tools.jackson..", "com.fasterxml..", "org.slf4j.."
                )
                .because("the rules are read here, without knowing Spring, JPA or Jackson")
                .check(classes)
        }

        then("it knows neither the services nor the persistence of its own module") {
            noClasses()
                .that().resideInAPackage("com.example.dpop.account.domain..")
                .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAnyPackage("com.example.dpop.account.application..", "com.example.dpop.account.infrastructure..")
                        .or(JavaClass.Predicates.simpleName("AccountService"))
                )
                .because("application and infrastructure use the domain, never the other way")
                .check(classes)
        }
    }

    given("the account module's packages") {
        then("they form a DAG") {
            slices().matching("com.example.dpop.account.(*)..").should().beFreeOfCycles().check(classes)
        }
    }
})

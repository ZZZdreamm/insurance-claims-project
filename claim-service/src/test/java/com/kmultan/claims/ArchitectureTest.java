package com.kmultan.claims;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The layering the rest of the codebase relies on, enforced instead of assumed:
 * domain knows nothing above it, application orchestrates domain only, and the
 * web layer never reaches around the application into adapters. Runs on plain
 * bytecode - no containers, fails the build like any other test.
 */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.kmultan.claims");

    @Test
    void domainDependsOnNothingAbove() {
        noClasses()
                .that()
                .resideInAPackage("..claims.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..claims.application..", "..claims.api..", "..claims.infrastructure..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void domainStaysOutOfTransportConcerns() {
        noClasses()
                .that()
                .resideInAPackage("..claims.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web..", "org.springframework.kafka..", "jakarta.servlet..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void applicationDependsOnlyOnDomain() {
        noClasses()
                .that()
                .resideInAPackage("..claims.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..claims.api..", "..claims.infrastructure..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The web layer talks to application services, never directly to adapters. The security package
     * is the one deliberate exception: the authenticated-principal type and the token service are
     * the seam between HTTP and the rest of the system.
     */
    @Test
    void apiDoesNotReachIntoAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..claims.api..")
                .should()
                .dependOnClassesThat(resideInAPackage("..claims.infrastructure..")
                        .and(not(resideInAPackage("..claims.infrastructure.security.."))))
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void noCyclesBetweenPackages() {
        slices().matching("com.kmultan.claims.(*)..").should().beFreeOfCycles().check(PRODUCTION_CLASSES);
    }

    @Test
    void controllersLiveInTheApiPackage() {
        classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .resideInAPackage("..claims.api..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void configurationClassesAreNamedAsSuch() {
        classes()
                .that()
                .areAnnotatedWith(Configuration.class)
                .should()
                .haveSimpleNameEndingWith("Configuration")
                .check(PRODUCTION_CLASSES);
    }
}

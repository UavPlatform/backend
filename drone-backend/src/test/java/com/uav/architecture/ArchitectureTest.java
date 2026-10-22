package com.uav.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Executable architecture rules.
 *
 * <p>Rules with legacy violations are frozen: the committed store records today's
 * debt, and any new violation fails the build. Rules that already hold are plain
 * rules. Never change a frozen rule description without refreezing intentionally.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.uav");

    private static final ArchRule NO_FIELD_INJECTION = FreezingArchRule.freeze(
            noFields().should().beAnnotatedWith(Autowired.class)
                    .as("no @Autowired field injection"));

    private static final ArchRule REPOSITORIES_MUST_NOT_DEPEND_ON_SERVICES =
            noClasses().that().resideInAnyPackage("..mapper..", "..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..service..")
                    .as("repositories must not depend on services");

    private static final ArchRule POJOS_MUST_NOT_DEPEND_ON_CONTROLLERS_OR_SERVICES =
            noClasses().that().resideInAPackage("..pojo..")
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..")
                    .as("pojos must not depend on controllers or services");

    private static final ArchRule CONTROLLERS_MUST_USE_SERVICES =
            FreezingArchRule.freeze(
                    noClasses().that().resideInAPackage("..controller..")
                            .should().dependOnClassesThat().resideInAnyPackage("..mapper..", "..repository..")
                            .as("controllers must use services, not repositories"));

    private static final ArchRule VO_SUFFIX = FreezingArchRule.freeze(
            noClasses().should().haveSimpleNameEndingWith("Vo")
                    .as("use VO suffix, not Vo"));

    private static final ArchRule DTO_SUFFIX = FreezingArchRule.freeze(
            noClasses().should().haveSimpleNameEndingWith("Dto")
                    .as("use DTO suffix, not Dto"));

    @Test
    void noFieldInjection() {
        NO_FIELD_INJECTION.check(CLASSES);
    }

    @Test
    void repositoriesDoNotDependOnServices() {
        REPOSITORIES_MUST_NOT_DEPEND_ON_SERVICES.check(CLASSES);
    }

    @Test
    void pojosDoNotDependOnControllersOrServices() {
        POJOS_MUST_NOT_DEPEND_ON_CONTROLLERS_OR_SERVICES.check(CLASSES);
    }

    @Test
    void controllersUseServices() {
        CONTROLLERS_MUST_USE_SERVICES.check(CLASSES);
    }

    @Test
    void voSuffix() {
        VO_SUFFIX.check(CLASSES);
    }

    @Test
    void dtoSuffix() {
        DTO_SUFFIX.check(CLASSES);
    }
}

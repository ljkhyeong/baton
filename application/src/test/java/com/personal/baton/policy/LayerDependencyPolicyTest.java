package com.personal.baton.policy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("policy")
class LayerDependencyPolicyTest {

    private static final String ROOT = "com.personal.baton";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @DisplayName("domain은 application을 참조하지 않는다")
    @Test
    void domainShouldNotDependOnApplication() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @DisplayName("domain은 adapter와 bootstrap을 참조하지 않는다")
    @Test
    void domainShouldNotDependOnOuterLayers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..bootstrap..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @DisplayName("application은 adapter와 bootstrap을 참조하지 않는다")
    @Test
    void applicationShouldNotDependOnOuterLayers() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..bootstrap..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @DisplayName("웹 어댑터는 출력 어댑터와 출력 포트를 직접 참조하지 않는다")
    @Test
    void webAdapterShouldOnlyCallInboundPorts() {
        noClasses()
                .that().resideInAPackage("..adapter.in.web..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.out..", "..port.out..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @DisplayName("출력 어댑터는 서로 또는 웹 어댑터를 참조하지 않는다")
    @Test
    void outputAdaptersShouldRemainIndependent() {
        noClasses()
                .that().resideInAPackage("..adapter.out..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.in.web..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @DisplayName("Spring Data repository는 DevTools 분리 클래스 로더에서도 프록시할 수 있게 공개한다")
    @Test
    void springDataRepositoriesShouldBePublic() {
        classes()
                .that().resideInAPackage("..adapter.out.persistence..")
                .and().haveSimpleNameEndingWith("JpaRepository")
                .should().bePublic()
                .allowEmptyShould(true)
                .check(classes);
    }
}

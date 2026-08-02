package com.personal.baton.policy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Version;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

@Tag("policy")
class JpaEntityVersionPolicyTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importDomainClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.personal.baton.domain");
    }

    @DisplayName("JPA 버전 필드는 신규 엔티티 판별을 위해 nullable Long 타입을 사용한다")
    @Test
    void jpaVersionFieldsShouldUseNullableLongType() {
        fields()
                .that().areAnnotatedWith(Version.class)
                .should().haveRawType(Long.class)
                .check(classes);
    }
}

package com.personal.baton.application.workspace;

import com.personal.baton.bootstrap.config.ProductionDatasourceEnvironmentPostProcessor;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

@Tag("usecase")
class ProductionDatasourceEnvironmentPostProcessorTest {

    private static final String SECURE_DATABASE_URL =
            "jdbc:mysql://mysql:3306/baton"
                    + "?sslMode=REQUIRED&allowPublicKeyRetrieval=false"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8";
    private static final String DATABASE_PASSWORD =
            "production-database-password-000000000001";

    private final ProductionDatasourceEnvironmentPostProcessor postProcessor =
            new ProductionDatasourceEnvironmentPostProcessor();

    private final ApplicationContextRunner productionContextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=production")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> postProcessor.postProcessEnvironment(
                    context.getEnvironment(),
                    null
            ));

    @DisplayName("기본 local 프로필은 개발용 데이터베이스 기본값을 유지한다")
    @Test
    void keepsDatasourceDefaultsInDefaultLocalProfile() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> postProcessor.postProcessEnvironment(
                        context.getEnvironment(),
                        null
                ))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                            .startsWith("jdbc:mysql://localhost:3306/baton?useSSL=false");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                            .isEqualTo("baton");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                            .isEqualTo("password");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.hikari.connection-init-sql"
                    )).isEqualTo("SET SESSION innodb_lock_wait_timeout=2");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.hikari.connection-timeout",
                            Long.class
                    )).isEqualTo(1_000L);
                    assertThat(context.getEnvironment().getProperty(
                            "spring.transaction.default-timeout"
                    )).isEqualTo("7s");
                });
    }

    @DisplayName("운영 프로필은 데이터베이스 주소·사용자·비밀번호가 없으면 시작을 거절한다")
    @ParameterizedTest
    @MethodSource("missingDatabaseSettings")
    void rejectsMissingDatabaseSettingInProduction(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String missingSetting
    ) {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + databaseUrl,
                        "DB_USERNAME=" + databaseUsername,
                        "DB_PASSWORD=" + databasePassword
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필에는 " + missingSetting + " 설정이 필요합니다"
                            );
                });
    }

    static Stream<Arguments> missingDatabaseSettings() {
        return Stream.of(
                argumentSet("데이터베이스 주소 없음",
                        "", "baton", DATABASE_PASSWORD, "DB_URL"),
                argumentSet("데이터베이스 사용자 없음",
                        SECURE_DATABASE_URL, "", DATABASE_PASSWORD, "DB_USERNAME"),
                argumentSet("데이터베이스 비밀번호 없음",
                        SECURE_DATABASE_URL, "baton", "", "DB_PASSWORD")
        );
    }

    @DisplayName("운영 프로필은 sslMode=REQUIRED 이상의 TLS를 정확히 한 번 지정하지 않은 데이터베이스 주소를 거절한다")
    @ParameterizedTest
    @MethodSource("databaseUrlsWithoutSingleRequiredTls")
    void rejectsDatabaseUrlWithoutSingleRequiredTlsInProduction(String databaseUrl) {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + databaseUrl,
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=" + DATABASE_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필의 DB_URL은 sslMode=REQUIRED 이상의 TLS를 정확히 한 번 지정해야 합니다"
                            );
                });
    }

    static Stream<Arguments> databaseUrlsWithoutSingleRequiredTls() {
        return Stream.of(
                argumentSet("TLS를 강제하지 않는 주소",
                        "jdbc:mysql://mysql:3306/baton?useSSL=false"),
                argumentSet("URL 조각 뒤의 가짜 TLS 설정",
                        "jdbc:mysql://mysql:3306/baton?sslMode=DISABLED#?sslMode=REQUIRED"),
                argumentSet("중복된 TLS 설정",
                        "jdbc:mysql://mysql:3306/baton?sslMode=REQUIRED&sslMode=DISABLED"),
                argumentSet("공백으로 숨긴 중복 TLS 설정",
                        "jdbc:mysql://mysql:3306/baton?sslMode=REQUIRED& sslMode=DISABLED")
        );
    }

    @DisplayName("운영 프로필은 호스트별 TLS 덮어쓰기가 있는 주소를 거절한다")
    @Test
    void rejectsHostSpecificTlsOverrideInProduction() {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=jdbc:mysql://address=(host=mysql)(port=3306)"
                                + "(sslMode=DISABLED)/baton?sslMode=REQUIRED",
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=" + DATABASE_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필의 DB_URL은 속성 없는 단일 MySQL 호스트만 허용합니다"
                            );
                });
    }

    @DisplayName("운영 프로필은 Hikari·Flyway의 대체 DB 연결 속성을 거절한다")
    @ParameterizedTest
    @MethodSource("alternativeConnectionProperties")
    void rejectsAlternativeConnectionPropertyInProduction(String overrideProperty, String reportedProperty) {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + SECURE_DATABASE_URL,
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=" + DATABASE_PASSWORD,
                        overrideProperty
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필은 대체 DB 연결 속성을 허용하지 않습니다: "
                                            + reportedProperty
                            );
                });
    }

    static Stream<Arguments> alternativeConnectionProperties() {
        return Stream.of(
                argumentSet("Hikari 드라이버 속성의 TLS 덮어쓰기",
                        "spring.datasource.hikari.data-source-properties.sslMode=DISABLED",
                        "spring.datasource.hikari.data-source-properties"),
                argumentSet("Hikari JDBC 주소 덮어쓰기",
                        "spring.datasource.hikari.jdbc-url="
                                + "jdbc:mysql://mysql:3306/baton?sslMode=DISABLED",
                        "spring.datasource.hikari.jdbc-url"),
                argumentSet("완화된 별칭으로 지정한 Hikari JNDI",
                        "spring.datasource.hikari.dataSourceJNDI=java:comp/env/jdbc/baton",
                        "spring.datasource.hikari.data-source-jndi"),
                argumentSet("빈 Hikari 비밀번호 덮어쓰기",
                        "spring.datasource.hikari.password=",
                        "spring.datasource.hikari.password"),
                argumentSet("Flyway 전용 JDBC 주소",
                        "spring.flyway.url="
                                + "jdbc:mysql://mysql:3306/baton?sslMode=DISABLED",
                        "spring.flyway.url"),
                argumentSet("Flyway JDBC 속성의 TLS 덮어쓰기",
                        "spring.flyway.jdbc-properties.sslMode=DISABLED",
                        "spring.flyway.jdbc-properties")
        );
    }

    @DisplayName("운영 프로필은 잠금 대기 제한이 아닌 DB 세션 초기화 SQL을 거절한다")
    @Test
    void rejectsUnsafeConnectionInitSqlInProduction() {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + SECURE_DATABASE_URL,
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=" + DATABASE_PASSWORD,
                        "spring.datasource.hikari.connection-init-sql=SET SESSION sql_mode=''"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필의 DB 세션 초기화 SQL은 잠금 대기 제한 설정만 허용합니다"
                            );
                });
    }

    @DisplayName("운영 프로필은 root 계정인 데이터베이스 사용자를 거절한다")
    @Test
    void rejectsRootDatabaseUsernameInProduction() {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + SECURE_DATABASE_URL,
                        "DB_USERNAME=root",
                        "DB_PASSWORD=" + DATABASE_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필의 DB_USERNAME은 root가 아닌 1~32자 영문·숫자·밑줄이어야 합니다"
                            );
                });
    }

    @DisplayName("운영 프로필은 약한 데이터베이스 비밀번호를 거절한다")
    @Test
    void rejectsWeakDatabasePasswordInProduction() {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + SECURE_DATABASE_URL,
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=too-short"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "운영 프로필의 DB_PASSWORD는 32~200자 URL 안전 ASCII여야 합니다"
                            );
                });
    }

    @DisplayName("운영 프로필은 명시적인 TLS 데이터베이스 설정을 허용한다")
    @Test
    void acceptsExplicitSecureDatasourceConfigurationInProduction() {
        productionContextRunner
                .withPropertyValues(
                        "DB_URL=" + SECURE_DATABASE_URL,
                        "DB_USERNAME=baton",
                        "DB_PASSWORD=" + DATABASE_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                            .isEqualTo(SECURE_DATABASE_URL);
                    assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                            .isEqualTo("baton");
                });
    }
}

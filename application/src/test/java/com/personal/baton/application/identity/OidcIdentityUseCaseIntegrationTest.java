package com.personal.baton.application.identity;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.OidcAccountResult;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
                BatonApplication.class,
                OidcIdentityUseCaseIntegrationTest.FixedClockConfiguration.class
        }
)
class OidcIdentityUseCaseIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final VerifiedOidcIdentity IDENTITY =
            new VerifiedOidcIdentity(
                    "https://login.example.com/oidc",
                    "concurrent-subject-1501"
            );

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_oidc_identity")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OidcIdentityUseCase useCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("같은 검증 OIDC 신원의 첫 로그인이 겹쳐도 실제 MySQL에서 하나의 내부 계정으로 수렴한다")
    @Test
    void convergesConcurrentFirstLoginOnOneInternalAccount() throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OidcAccountResult> first = executor.submit(() -> resolveAfterBarrier(start));
            Future<OidcAccountResult> second = executor.submit(() -> resolveAfterBarrier(start));
            start.await(10, TimeUnit.SECONDS);

            List<OidcAccountResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertThat(results)
                    .extracting(OidcAccountResult::accountId)
                    .containsOnly(results.getFirst().accountId());
            assertThat(results)
                    .extracting(OidcAccountResult::createdAt)
                    .containsOnly(NOW);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oidc_external_identities",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_accounts",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT issuer, subject, BIN_TO_UUID(user_account_id) AS account_id "
                            + "FROM oidc_external_identities"
            )).containsEntry("issuer", IDENTITY.issuer())
                    .containsEntry("subject", IDENTITY.subject())
                    .containsEntry("account_id", results.getFirst().accountId().toString());
        } finally {
            executor.shutdownNow();
        }
    }

    private OidcAccountResult resolveAfterBarrier(CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return useCase.resolveAccount(IDENTITY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

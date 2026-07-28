package com.personal.baton.bootstrap.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public class ProductionDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Pattern DATABASE_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final Pattern DATABASE_PASSWORD = Pattern.compile("[A-Za-z0-9._~-]{32,200}");
    private static final Pattern SINGLE_HOST_AUTHORITY = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::([0-9]{1,5}))?"
    );
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Set<String> SECURE_MYSQL_SSL_MODES = Set.of(
            "REQUIRED",
            "VERIFY_CA",
            "VERIFY_IDENTITY"
    );
    private static final List<String> ALTERNATE_DATASOURCE_PROPERTIES = List.of(
            "spring.datasource.jndi-name",
            "spring.datasource.driver-class-name",
            "spring.datasource.type",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.driver-class-name",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-jndi"
    );
    private static final List<String> ALTERNATE_FLYWAY_PROPERTIES = List.of(
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.flyway.driver-class-name"
    );

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        if (!environment.acceptsProfiles(Profiles.of("production"))) {
            return;
        }

        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        requireConfigured(url, "DB_URL");
        requireConfigured(username, "DB_USERNAME");
        requireConfigured(password, "DB_PASSWORD");
        rejectAlternateDatasourceConfiguration(environment);
        rejectAlternateFlywayConfiguration(environment);
        requireSecureMysqlUrl(url);
        requireSafeUsername(username);
        requireSafePassword(password);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "production 프로필에는 " + environmentName + " 설정이 필요합니다"
            );
        }
    }

    private void requireSecureMysqlUrl(String url) {
        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        if (!normalizedUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(
                    "production 프로필의 DB_URL은 MySQL JDBC 주소여야 합니다"
            );
        }
        if (url.indexOf('#') >= 0) {
            throw insecureTlsUrl();
        }
        if (url.codePoints().anyMatch(Character::isWhitespace)) {
            throw insecureTlsUrl();
        }

        int authorityStart = "jdbc:mysql://".length();
        int databasePathStart = url.indexOf('/', authorityStart);
        int queryStart = databasePathStart < 0 ? -1 : url.indexOf('?', databasePathStart);
        if (queryStart < 0 || queryStart == url.length() - 1) {
            throw insecureTlsUrl();
        }
        requireSingleHostAuthority(url.substring(authorityStart, databasePathStart));
        String databaseName = url.substring(databasePathStart + 1, queryStart);
        if (!DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalStateException(
                    "production 프로필의 DB_URL은 단일 데이터베이스 이름을 가져야 합니다"
            );
        }

        String sslMode = null;
        try {
            for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
                int valueSeparator = parameter.indexOf('=');
                String rawName = valueSeparator < 0
                        ? parameter
                        : parameter.substring(0, valueSeparator);
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                if (!name.equals(name.strip())) {
                    throw insecureTlsUrl();
                }
                if (!name.equalsIgnoreCase("sslMode")) {
                    continue;
                }
                if (sslMode != null) {
                    throw insecureTlsUrl();
                }
                String rawValue = valueSeparator < 0
                        ? ""
                        : parameter.substring(valueSeparator + 1);
                sslMode = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException exception) {
            throw insecureTlsUrl();
        }

        if (sslMode == null
                || !SECURE_MYSQL_SSL_MODES.contains(sslMode.toUpperCase(Locale.ROOT))) {
            throw insecureTlsUrl();
        }
    }

    private void rejectAlternateDatasourceConfiguration(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        for (String propertyName : ALTERNATE_DATASOURCE_PROPERTIES) {
            if (binder.bind(propertyName, String.class).isBound()) {
                throw alternateDatasourceProperty(propertyName);
            }
        }

        Map<String, String> driverProperties = binder
                .bind(
                        "spring.datasource.hikari.data-source-properties",
                        Bindable.mapOf(String.class, String.class)
                )
                .orElse(Map.of());
        if (!driverProperties.isEmpty()) {
            throw alternateDatasourceProperty(
                    "spring.datasource.hikari.data-source-properties"
            );
        }
    }

    private void rejectAlternateFlywayConfiguration(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        for (String propertyName : ALTERNATE_FLYWAY_PROPERTIES) {
            if (binder.bind(propertyName, String.class).isBound()) {
                throw alternateDatasourceProperty(propertyName);
            }
        }

        Map<String, String> jdbcProperties = binder
                .bind(
                        "spring.flyway.jdbc-properties",
                        Bindable.mapOf(String.class, String.class)
                )
                .orElse(Map.of());
        if (!jdbcProperties.isEmpty()) {
            throw alternateDatasourceProperty("spring.flyway.jdbc-properties");
        }
    }

    private void requireSingleHostAuthority(String authority) {
        Matcher matcher = SINGLE_HOST_AUTHORITY.matcher(authority);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "production 프로필의 DB_URL은 속성 없는 단일 MySQL host만 허용합니다"
            );
        }

        String port = matcher.group(1);
        if (port != null) {
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                throw new IllegalStateException(
                        "production 프로필의 DB_URL port는 1~65535 범위여야 합니다"
                );
            }
        }
    }

    private IllegalStateException alternateDatasourceProperty(String propertyName) {
        return new IllegalStateException(
                "production 프로필은 대체 DB 연결 속성을 허용하지 않습니다: " + propertyName
        );
    }

    private IllegalStateException insecureTlsUrl() {
        return new IllegalStateException(
                "production 프로필의 DB_URL은 sslMode=REQUIRED 이상의 TLS를 정확히 한 번 지정해야 합니다"
        );
    }

    private void requireSafeUsername(String username) {
        if (!DATABASE_USERNAME.matcher(username).matches()
                || username.equalsIgnoreCase("root")) {
            throw new IllegalStateException(
                    "production 프로필의 DB_USERNAME은 root가 아닌 1~32자 영문·숫자·밑줄이어야 합니다"
            );
        }
    }

    private void requireSafePassword(String password) {
        if (!DATABASE_PASSWORD.matcher(password).matches()) {
            throw new IllegalStateException(
                    "production 프로필의 DB_PASSWORD는 32~200자 URL-safe ASCII여야 합니다"
            );
        }
    }
}

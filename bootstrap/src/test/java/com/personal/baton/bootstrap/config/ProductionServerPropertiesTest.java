package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.Session.SessionTrackingMode;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProductionServerPropertiesTest {

    @DisplayName("production 세션 보안은 Boot 표준 server properties에 바인딩된다")
    @Test
    void bindsProductionSessionSecurityProperties() throws Exception {
        var environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load(
                "production",
                new ClassPathResource("application-production.yml")
        );
        sources.forEach(environment.getPropertySources()::addLast);

        ServerProperties properties = Binder.get(environment)
                .bind("server", Bindable.of(ServerProperties.class))
                .orElseThrow(() -> new AssertionError("production server 설정을 바인딩하지 못했습니다"));
        var session = properties.getServlet().getSession();
        Cookie cookie = session.getCookie();

        assertThat(session.getTrackingModes()).isEqualTo(Set.of(SessionTrackingMode.COOKIE));
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
    }
}

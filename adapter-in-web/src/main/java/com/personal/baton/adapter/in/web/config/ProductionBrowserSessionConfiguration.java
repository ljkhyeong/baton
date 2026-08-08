package com.personal.baton.adapter.in.web.config;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("production")
public class ProductionBrowserSessionConfiguration {

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> secureSessionCookieCustomizer() {
        return factory -> secureSessionCookie(factory.getSettings().getSession());
    }

    static void secureSessionCookie(Session session) {
        Cookie cookie = session.getCookie();
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setSameSite(Cookie.SameSite.LAX);
        cookie.setPath("/");
    }
}

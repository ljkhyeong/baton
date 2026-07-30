package com.personal.baton.adapter.in.web.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.SecurityContextRepository;

@FunctionalInterface
interface OidcLoginSecurityConfigurer {

    void configure(
            HttpSecurity http,
            SecurityContextRepository sessionSecurityContextRepository
    ) throws Exception;
}

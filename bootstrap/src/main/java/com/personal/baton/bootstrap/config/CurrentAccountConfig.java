package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.auth.CurrentAuthenticatedAccount;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CurrentAccountConfig {
    @Bean
    CurrentAccountProvider currentAccountProvider() { return CurrentAuthenticatedAccount::accountId; }
}

package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class WebFilterConfig {

    @Bean
    @FilterRegistration(
            name = "requestIdFilter",
            order = Ordered.HIGHEST_PRECEDENCE,
            asyncSupported = true,
            dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR}
    )
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }
}

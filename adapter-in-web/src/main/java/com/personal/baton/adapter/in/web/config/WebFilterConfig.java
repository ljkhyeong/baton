package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class WebFilterConfig {

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(
            RequestIdFilter requestIdFilter
    ) {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(requestIdFilter);
        registration.setName("requestIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR
        );
        return registration;
    }
}

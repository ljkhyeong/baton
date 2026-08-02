package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<WatchEventReceiverAuthentication> receiverAuthenticationProvider
    ) throws Exception {
        WatchEventReceiverAuthentication receiverAuthentication = receiverAuthenticationProvider
                .getIfAvailable(WatchEventReceiverAuthentication::disabled);
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/v1/workspaces",
                        "/api/v1/teams/*/seasons/*/**",
                        WatchHealthEventController.PATH
                ))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/api/v1/system/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workspaces").permitAll()
                        .requestMatchers(HttpMethod.POST, WatchHealthEventController.PATH).permitAll()
                        .requestMatchers("/api/v1/teams/*/seasons/*/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new WatchEventReceiverAuthenticationFilter(receiverAuthentication),
                        AnonymousAuthenticationFilter.class
                )
                .build();
    }
}

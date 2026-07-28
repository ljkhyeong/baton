package com.personal.baton;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
)
public class BatonApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatonApplication.class, args);
    }
}

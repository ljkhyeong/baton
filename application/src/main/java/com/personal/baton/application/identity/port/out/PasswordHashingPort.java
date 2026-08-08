package com.personal.baton.application.identity.port.out;

public interface PasswordHashingPort {

    String encode(String rawPassword);
}

package com.personal.baton.domain.identity;

public enum IdentityProvider {
    GOOGLE,
    NAVER,
    LOCAL_EMAIL;

    public boolean isExternal() {
        return this != LOCAL_EMAIL;
    }
}

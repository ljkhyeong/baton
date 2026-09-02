package com.personal.baton.adapter.in.web.auth;

import java.util.UUID;

public interface AuthenticatedAccountPrincipal {

    UUID accountId();

    long sessionVersion();
}

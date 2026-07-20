package com.personal.baton.adapter.in.web.system;

import java.time.Instant;

public record SystemStatusResponse(String service, Instant checkedAt) {
}

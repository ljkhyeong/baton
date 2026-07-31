package com.personal.baton.adapter.in.web.round;

import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.PublicRoundJwkSet;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoundJwkSetController {

    private static final CacheControl PUBLIC_KEY_CACHE = CacheControl
            .maxAge(Duration.ofSeconds(60))
            .cachePublic()
            .mustRevalidate();

    private final RoundParticipationGrantUseCase useCase;

    public RoundJwkSetController(RoundParticipationGrantUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping(
            value = "/.well-known/jwks.json",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> getPublicJwkSet() {
        PublicRoundJwkSet jwkSet = useCase.getPublicJwkSet();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(PUBLIC_KEY_CACHE)
                .eTag(jwkSet.etag())
                .body(jwkSet.body());
    }
}

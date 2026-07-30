package com.personal.baton.adapter.in.web.round;

import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.PublicRoundJwkSet;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<String> getPublicJwkSet(
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch
    ) {
        PublicRoundJwkSet jwkSet = useCase.getPublicJwkSet();
        String quotedEtag = "\"" + jwkSet.etag() + "\"";
        if (etagMatches(ifNoneMatch, quotedEtag)) {
            return ResponseEntity.status(304)
                    .cacheControl(PUBLIC_KEY_CACHE)
                    .eTag(jwkSet.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(PUBLIC_KEY_CACHE)
                .eTag(jwkSet.etag())
                .body(jwkSet.body());
    }

    private boolean etagMatches(String ifNoneMatch, String expected) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim)
                .anyMatch(candidate -> candidate.equals("*")
                        || candidate.equals(expected)
                        || candidate.equals("W/" + expected));
    }
}

package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.port.in.ReadParticipationGrantJwkSetUseCase;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import org.springframework.stereotype.Service;

@Service
public class ParticipationGrantJwkSetService implements ReadParticipationGrantJwkSetUseCase {

    private final ParticipationGrantJwkSetProvider jwkSetProvider;

    public ParticipationGrantJwkSetService(ParticipationGrantJwkSetProvider jwkSetProvider) {
        this.jwkSetProvider = jwkSetProvider;
    }

    @Override
    public String readPublicJwkSetJson() {
        return jwkSetProvider.readPublicJwkSetJson();
    }
}

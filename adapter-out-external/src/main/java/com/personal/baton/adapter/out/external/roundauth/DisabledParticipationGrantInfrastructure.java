package com.personal.baton.adapter.out.external.roundauth;

import com.personal.baton.application.roundauth.error.ParticipationGrantUnavailableException;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;

public final class DisabledParticipationGrantInfrastructure
        implements ParticipationGrantSigner, ParticipationGrantJwkSetProvider {

    private static final String MESSAGE = "ROUND 참여권 발급이 비활성화되어 있습니다";

    @Override
    public String sign(ParticipationGrantClaims claims) {
        throw new ParticipationGrantUnavailableException(MESSAGE);
    }

    @Override
    public String readPublicJwkSetJson() {
        throw new ParticipationGrantUnavailableException(MESSAGE);
    }
}

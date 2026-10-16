package com.personal.baton.adapter.in.web.system;

import com.personal.baton.application.system.port.in.GetSystemStatusUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemStatusController {

    public static final String PATH = "/api/v1/system/status";

    private final GetSystemStatusUseCase getSystemStatusUseCase;

    public SystemStatusController(GetSystemStatusUseCase getSystemStatusUseCase) {
        this.getSystemStatusUseCase = getSystemStatusUseCase;
    }

    @GetMapping(PATH)
    public SystemStatusResponse getStatus() {
        GetSystemStatusUseCase.SystemStatusResult result = getSystemStatusUseCase.getStatus();
        return new SystemStatusResponse(result.service(), result.checkedAt());
    }
}

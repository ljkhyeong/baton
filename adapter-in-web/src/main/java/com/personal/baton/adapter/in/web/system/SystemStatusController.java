package com.personal.baton.adapter.in.web.system;

import com.personal.baton.application.system.port.in.GetSystemStatusUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final GetSystemStatusUseCase getSystemStatusUseCase;

    public SystemStatusController(GetSystemStatusUseCase getSystemStatusUseCase) {
        this.getSystemStatusUseCase = getSystemStatusUseCase;
    }

    @GetMapping("/status")
    public SystemStatusResponse getStatus() {
        GetSystemStatusUseCase.SystemStatusResult result = getSystemStatusUseCase.getStatus();
        return new SystemStatusResponse(result.service(), result.checkedAt());
    }
}

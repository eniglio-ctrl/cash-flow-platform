package br.com.cashflow.consolidation.api;

import br.com.cashflow.consolidation.infrastructure.messaging.DeadLetterReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "admin", description = "Operação: reprocessamento da DLQ")
public class AdminController {

    private final DeadLetterReplayService replay;

    public AdminController(DeadLetterReplayService replay) {
        this.replay = replay;
    }

    @PostMapping("/dlq/replay")
    @Operation(summary = "Reenvia mensagens da DLQ para o tópico principal (escopo consolidado:admin)")
    public Map<String, Integer> replay(@RequestParam(defaultValue = "100") int max) throws Exception {
        return Map.of("replayed", replay.replay(Math.min(Math.max(max, 1), 1000)));
    }
}

package br.com.cashflow.consolidation.api;

import br.com.cashflow.consolidation.application.BalanceQueryService;
import br.com.cashflow.consolidation.domain.DailyBalance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consolidados")
@SecurityRequirement(name = "bearer")
public class BalanceController {

    private final BalanceQueryService service;

    public BalanceController(BalanceQueryService service) {
        this.service = service;
    }

    @GetMapping("/{date}")
    @Operation(summary = "Saldo consolidado de um dia",
            description = "Eventualmente consistente: o cabeçalho X-Consistency informa o modelo.")
    public ResponseEntity<DailyBalance> day(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(service.day(merchant(jwt), date));
    }

    @GetMapping
    @Operation(summary = "Saldos consolidados de um intervalo (máx. 31 dias)")
    public ResponseEntity<List<DailyBalance>> range(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ok(service.range(merchant(jwt), from, to));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Consistency", "eventual")
                .body(body);
    }

    private static UUID merchant(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

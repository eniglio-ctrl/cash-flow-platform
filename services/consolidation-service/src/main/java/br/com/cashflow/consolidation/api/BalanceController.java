package br.com.cashflow.consolidation.api;

import br.com.cashflow.consolidation.application.BalanceQueryService;
import br.com.cashflow.consolidation.domain.DailyBalance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ProblemDetail;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "consolidados", description = "Saldo consolidado por dia e por período")
public class BalanceController {

    private final BalanceQueryService service;

    public BalanceController(BalanceQueryService service) {
        this.service = service;
    }

    @GetMapping("/{date}")
    @Operation(summary = "Saldo consolidado de um dia",
            description = "Eventualmente consistente: o cabeçalho X-Consistency informa o modelo.")
    @ApiResponse(responseCode = "200", description = "Saldo do dia (dia sem movimento retorna totais zerados e saldo encadeado)",
            headers = @Header(name = "X-Consistency", description = "Sempre \"eventual\": o saldo reflete os eventos já processados"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = DailyBalance.class)))
    @ApiResponse(responseCode = "400", description = "Data inválida", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DailyBalance> day(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(example = "2026-10-13") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(service.day(merchant(jwt), date));
    }

    @GetMapping
    @Operation(summary = "Saldos consolidados de um intervalo (máx. 31 dias)")
    @ApiResponse(responseCode = "200", description = "Um item por dia do intervalo, com saldos encadeados", headers = @Header(name = "X-Consistency", description = "Sempre \"eventual\": o saldo reflete os eventos já processados"),
            content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = DailyBalance.class))))
    @ApiResponse(responseCode = "400", description = "Datas inválidas, 'to' anterior a 'from' ou intervalo acima de 31 dias", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<List<DailyBalance>> range(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(example = "2026-10-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @Parameter(example = "2026-10-13") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
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

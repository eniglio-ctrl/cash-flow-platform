package br.com.cashflow.launch.api;

import br.com.cashflow.launch.application.EntryResult;
import br.com.cashflow.launch.application.EntryService;
import br.com.cashflow.launch.application.RegisterEntryCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * O comerciante é SEMPRE obtido do token (claim "sub"), nunca do payload,
 * impedindo que um comerciante leia ou grave dados de outro.
 */
@RestController
@Validated
@RequestMapping("/api/v1/lancamentos")
@SecurityRequirement(name = "bearer")
@Tag(name = "lancamentos", description = "Registro, estorno e consulta de lançamentos")
public class EntryController {

    private static final String KEY_PATTERN = "^[A-Za-z0-9_\\-:.]{8,100}$";
    private static final String KEY_DOC = "Gerada pelo cliente para identificar uma operação de negócio (recomendado: UUID v4). "
            + "Nova operação: nova chave. Retry da mesma operação (timeout, erro de rede, 5xx): reutilize a mesma chave. "
            + "Mesma chave e mesmo conteúdo: 200 com o resultado original. Mesma chave e conteúdo diferente: 409. "
            + "Única por comerciante entre todas as escritas; só é registrada quando a operação é concluída com sucesso; não expira. "
            + "8 a 100 caracteres: letras, dígitos, _ - : .";

    private final EntryService service;

    public EntryController(EntryService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Registra um crédito ou débito",
            description = "Idempotente pela Idempotency-Key: repetir a mesma requisição retorna o lançamento original (200).")
    @ApiResponse(responseCode = "201", description = "Lançamento criado",
            headers = @Header(name = "Location", description = "URI do lançamento criado"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "200", description = "Repetição idempotente: mesma Idempotency-Key e mesmo payload; devolve o lançamento original",
            headers = @Header(name = "Idempotent-Replayed", description = "Sempre \"true\""), content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Payload inválido ou Idempotency-Key ausente/fora do padrão", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key já usada com payload diferente", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Regra de negócio violada (ex.: data futura, valor fora do limite)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<EntryResponse> register(@AuthenticationPrincipal Jwt jwt,
                                                  @Parameter(description = KEY_DOC, example = "venda-0001-2026-10-13")
                                                  @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
                                                  @Valid @RequestBody EntryRequest body) {
        EntryResult result = service.register(new RegisterEntryCommand(merchant(jwt), key, body.date(),
                body.type(), body.amount(), body.description()));
        return respond(result);
    }

    @PostMapping("/{id}/estornos")
    @Operation(summary = "Estorna um lançamento (cria lançamento inverso na data de hoje)")
    @ApiResponse(responseCode = "201", description = "Estorno criado",
            headers = @Header(name = "Location", description = "URI do estorno criado"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "200", description = "Repetição idempotente do mesmo estorno",
            headers = @Header(name = "Idempotent-Replayed", description = "Sempre \"true\""), content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Requisição inválida", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Lançamento não encontrado para este comerciante", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key já usada com payload diferente", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Lançamento já estornado, ou tentativa de estornar um estorno", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<EntryResponse> reverse(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID id,
                                                 @Parameter(description = KEY_DOC, example = "estorno-0001-2026-10-13")
                                                 @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
                                                 @Valid @RequestBody(required = false) ReversalRequest body) {
        String reason = body == null ? null : body.reason();
        return respond(service.reverse(merchant(jwt), id, key, reason));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um lançamento pelo id")
    @ApiResponse(responseCode = "200", description = "Lançamento", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "404", description = "Não encontrado para este comerciante", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public EntryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return EntryResponse.from(service.find(merchant(jwt), id));
    }

    @GetMapping
    @Operation(summary = "Lista os lançamentos de uma data")
    @ApiResponse(responseCode = "200", description = "Lançamentos da data, em ordem de registro",
            content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = EntryResponse.class))))
    @ApiResponse(responseCode = "400", description = "Parâmetros inválidos", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<EntryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                    @Parameter(example = "2026-10-13") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.listByDate(merchant(jwt), date, page, size).stream().map(EntryResponse::from).toList();
    }

    private static ResponseEntity<EntryResponse> respond(EntryResult result) {
        EntryResponse body = EntryResponse.from(result.entry());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/lancamentos/" + body.id()))
                .body(body);
    }

    private static UUID merchant(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

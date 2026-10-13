package br.com.cashflow.launch.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Emissor de tokens APENAS para ambiente local (profile "local"). Substitui o IdP corporativo
 * (Keycloak/Entra ID/Cognito) para que o avaliador consiga testar sem infraestrutura extra.
 */
@RestController
@Profile("local")
@Tag(name = "dev-token", description = "Emissão de token para testes locais (substitui o IdP)")
public class DevTokenController {

    private static final String DEFAULT_SCOPES = "lancamentos:write lancamentos:read consolidado:read";

    private final NimbusJwtEncoder encoder;
    private final String issuer;
    private final String audience;

    public DevTokenController(@Value("${cashflow.security.jwt.secret}") String secret,
                              @Value("${cashflow.security.jwt.issuer}") String issuer,
                              @Value("${cashflow.security.jwt.audience}") String audience) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        this.issuer = issuer;
        this.audience = audience;
    }

    @PostMapping("/dev/token")
    @Operation(summary = "Gera um JWT de teste (somente profile local)",
            description = "Sem merchantId, cria um comerciante novo. Copie o access_token e use em Authorize.")
    public Map<String, Object> token(@RequestParam(required = false) UUID merchantId,
                                     @RequestParam(defaultValue = DEFAULT_SCOPES) String scope) {
        UUID merchant = merchantId != null ? merchantId : UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(java.util.List.of(audience))
                .subject(merchant.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(8)))
                .claim("scope", scope)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("access_token", token, "token_type", "Bearer", "merchant_id", merchant, "scope", scope);
    }
}

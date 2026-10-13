package br.com.cashflow.launch.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Lançamentos").version("v1")
                        .description("Registro de créditos e débitos do comerciante. No ambiente local a página já abre autorizada com um token de teste; basta usar Try it out e Execute. Todo POST exige o header Idempotency-Key (8 a 100 caracteres): repetir a mesma chave devolve o lançamento original (200), sem duplicar."))
                // 1º servidor relativo: na Swagger UI as chamadas vão para o gateway que serviu o contrato,
                // qualquer que seja a porta. O 2º serve para quem abre o JSON fora do gateway (ex.: Swagger Editor).
                .servers(List.of(
                        new Server().url("/").description("Gateway que serviu esta especificação"),
                        new Server().url("http://localhost:8080").description("Gateway local (Docker Compose, porta padrão)")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    /**
     * Respostas comuns a toda operação protegida: geradas pelo Spring Security (401/403, sem corpo)
     * e pelo gateway (429), por isso não aparecem nas anotações dos controllers.
     */
    @Bean
    OpenApiCustomizer commonResponses() {
        return openApi -> openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
            if (op.getSecurity() == null || op.getSecurity().isEmpty()) {
                return;
            }
            op.getResponses().addApiResponse("401", new ApiResponse()
                    .description("Token ausente, inválido ou expirado (header WWW-Authenticate: Bearer)"));
            op.getResponses().addApiResponse("403", new ApiResponse()
                    .description("Token válido, mas sem o escopo exigido pela operação"));
            op.getResponses().addApiResponse("429", new ApiResponse()
                    .description("Limite de requisições por token excedido (aplicado pelo gateway)"));
        }));
    }
}

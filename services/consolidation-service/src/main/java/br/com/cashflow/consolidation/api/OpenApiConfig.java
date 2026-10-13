package br.com.cashflow.consolidation.api;

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
                .info(new Info().title("Consolidado diário").version("v1")
                        .description("Saldo diário consolidado (eventualmente consistente). No ambiente local a autorização é automática e vale para as duas APIs."))
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

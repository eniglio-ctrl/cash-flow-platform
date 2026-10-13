package br.com.cashflow.consolidation.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Consolidado diário").version("v1")
                        .description("Saldo diário consolidado (eventualmente consistente). Gere o token na API de Lançamentos (POST /dev/token) e use em Authorize."))
                // URL relativa: as chamadas da Swagger UI passam pelo gateway que serviu a especificação
                .servers(List.of(new Server().url("/").description("Gateway")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}

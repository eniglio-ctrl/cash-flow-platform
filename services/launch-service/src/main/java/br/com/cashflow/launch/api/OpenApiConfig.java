package br.com.cashflow.launch.api;

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
                .info(new Info().title("Lançamentos").version("v1")
                        .description("Registro de créditos e débitos do comerciante. Para testar: execute POST /dev/token (somente ambiente local), copie o access_token, clique em Authorize e cole o token. Todo POST exige o header Idempotency-Key (8 a 100 caracteres)."))
                // URL relativa: as chamadas da Swagger UI passam pelo gateway que serviu a especificação
                .servers(List.of(new Server().url("/").description("Gateway")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}

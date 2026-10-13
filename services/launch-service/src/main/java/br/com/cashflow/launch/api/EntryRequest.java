package br.com.cashflow.launch.api;

import br.com.cashflow.launch.domain.EntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EntryRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-13",
                description = "Data financeira no fuso do comerciante. Não pode ser futura nem anterior a 365 dias.")
        @NotNull LocalDate date,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "CREDIT") @NotNull EntryType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "150.75",
                description = "Valor positivo, no máximo 2 casas decimais. O tipo define o sinal.") @NotNull @DecimalMin(value = "0.01") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Venda balcão", minLength = 1,
                description = "Obrigatória e não pode ser vazia.") @NotBlank @Size(max = 200) String description) {
}

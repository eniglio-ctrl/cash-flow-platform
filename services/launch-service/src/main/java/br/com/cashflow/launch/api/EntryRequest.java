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
        @Schema(example = "2026-10-13", description = "Data financeira (fuso do comerciante)")
        @NotNull LocalDate date,
        @Schema(example = "CREDIT") @NotNull EntryType type,
        @Schema(example = "150.75") @NotNull @DecimalMin(value = "0.01") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Schema(example = "Venda balcão") @NotBlank @Size(max = 200) String description) {
}

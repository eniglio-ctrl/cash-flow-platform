package br.com.cashflow.launch.api;

import jakarta.validation.constraints.Size;

public record ReversalRequest(@Size(max = 200) String reason) {
}

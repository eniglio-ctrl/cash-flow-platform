package br.com.cashflow.launch.application;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key já utilizada com um payload diferente");
    }
}

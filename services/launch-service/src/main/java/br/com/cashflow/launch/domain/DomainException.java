package br.com.cashflow.launch.domain;

/** Violação de regra de negócio. Mapeada para HTTP 422. */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }
}

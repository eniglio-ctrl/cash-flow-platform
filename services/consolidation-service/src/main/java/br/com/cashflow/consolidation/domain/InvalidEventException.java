package br.com.cashflow.consolidation.domain;

/** Evento malformado: vai direto para a DLQ, sem retry. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}

package br.com.cashflow.launch.domain;

public enum EntryType {
    CREDIT,
    DEBIT;

    public EntryType opposite() {
        return this == CREDIT ? DEBIT : CREDIT;
    }
}

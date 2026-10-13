package br.com.cashflow.launch.application;

import br.com.cashflow.launch.domain.Entry;

/** @param replayed true quando a resposta veio de uma requisição anterior com a mesma Idempotency-Key. */
public record EntryResult(Entry entry, boolean replayed) {
}

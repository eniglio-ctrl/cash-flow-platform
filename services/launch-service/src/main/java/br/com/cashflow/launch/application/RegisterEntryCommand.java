package br.com.cashflow.launch.application;

import br.com.cashflow.launch.domain.EntryType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

public record RegisterEntryCommand(UUID merchantId, String idempotencyKey, LocalDate entryDate,
                                   EntryType type, BigDecimal amount, String description) {

    /** Hash do conteúdo da requisição, usado para detectar reuso da chave com payload diferente. */
    public String fingerprint() {
        return sha256("REGISTER|" + entryDate + "|" + type + "|" + amount.stripTrailingZeros().toPlainString()
                + "|" + description);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package br.com.cashflow.consolidation.application;

import br.com.cashflow.consolidation.domain.DailyBalance;
import br.com.cashflow.consolidation.infrastructure.persistence.ConsolidationRepository;
import br.com.cashflow.consolidation.infrastructure.persistence.ConsolidationRepository.DayRow;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Caminho de leitura (alvo de 50 req/s). Lê do read model pré-calculado e usa cache local
 * de TTL curto: o consolidado já é eventualmente consistente, então alguns segundos de
 * defasagem adicional são aceitáveis e retiram a maior parte da carga do banco em picos.
 */
@Service
public class BalanceQueryService {

    public static final int MAX_RANGE_DAYS = 31;

    private final ConsolidationRepository repository;

    public BalanceQueryService(ConsolidationRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = "daily-balance", key = "#merchantId + ':' + #date")
    public DailyBalance day(UUID merchantId, LocalDate date) {
        return range(merchantId, date, date).getFirst();
    }

    @Cacheable(cacheNames = "balance-range", key = "#merchantId + ':' + #from + ':' + #to")
    public List<DailyBalance> range(UUID merchantId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' deve ser maior ou igual a 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Intervalo máximo de " + MAX_RANGE_DAYS + " dias");
        }
        BigDecimal running = repository.balanceBefore(merchantId, from);
        Map<LocalDate, DayRow> rows = repository.findBetween(merchantId, from, to).stream()
                .collect(Collectors.toMap(DayRow::date, Function.identity()));
        List<DailyBalance> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayRow row = rows.get(d);
            DailyBalance balance = row == null
                    ? DailyBalance.of(d, running, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 0, null)
                    : DailyBalance.of(d, running, row.credits(), row.debits(), row.count(), row.updatedAt());
            result.add(balance);
            running = balance.closingBalance();
        }
        return result;
    }
}

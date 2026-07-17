package com.eventledger.account.service;

import com.eventledger.account.api.dto.TransactionRequest;
import com.eventledger.account.api.error.AccountNotFoundException;
import com.eventledger.account.api.error.TransactionConflictException;
import com.eventledger.account.domain.AccountTransaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int RECENT_TRANSACTIONS_LIMIT = 10;

    private final TransactionRepository repository;

    public AccountService(TransactionRepository repository) {
        this.repository = repository;
    }

    /**
     * Idempotent application, same strategy as the Gateway: INSERT first and
     * let the unique constraint on event_id arbitrate. Replay of an identical
     * transaction returns the original application (harmless); a conflicting
     * reuse of the eventId is rejected with 409.
     *
     * Not @Transactional for the same reason as the Gateway's EventService:
     * the recovery SELECT after a constraint violation must run in a fresh
     * transaction, not one poisoned by the failed insert.
     */
    public ApplicationResult apply(String accountId, TransactionRequest request) {
        TransactionType type = TransactionType.valueOf(request.type());
        try {
            AccountTransaction applied = repository.save(new AccountTransaction(
                    request.eventId(),
                    accountId,
                    type,
                    request.amount(),
                    request.currency(),
                    request.eventTimestamp()
            ));
            log.info("Applied transaction eventId={} accountId={} type={} amount={}",
                    applied.getEventId(), accountId, type, request.amount());
            return new ApplicationResult(applied, true);
        } catch (DataIntegrityViolationException duplicate) {
            AccountTransaction existing = repository.findByEventId(request.eventId())
                    .orElseThrow(() -> duplicate);

            if (!existing.matches(accountId, type, request.amount(),
                    request.currency(), request.eventTimestamp())) {
                log.warn("Conflicting transaction replay for eventId={}", request.eventId());
                throw new TransactionConflictException(request.eventId());
            }
            log.info("Replay of already-applied transaction eventId={}, returning original",
                    request.eventId());
            return new ApplicationResult(existing, false);
        }
    }

    public BigDecimal balanceFor(String accountId) {
        requireKnownAccount(accountId);
        return repository.balanceFor(accountId);
    }

    public long transactionCountFor(String accountId) {
        return repository.countByAccountId(accountId);
    }

    public List<AccountTransaction> recentTransactionsFor(String accountId) {
        requireKnownAccount(accountId);
        return repository.findByAccountIdOrderByEventTimestampDesc(
                accountId, PageRequest.of(0, RECENT_TRANSACTIONS_LIMIT));
    }

    private void requireKnownAccount(String accountId) {
        if (!repository.existsByAccountId(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }

    public record ApplicationResult(AccountTransaction transaction, boolean applied) {
    }
}

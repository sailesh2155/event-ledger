package com.eventledger.account.repository;

import com.eventledger.account.domain.AccountTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Optional<AccountTransaction> findByEventId(String eventId);

    boolean existsByAccountId(String accountId);

    long countByAccountId(String accountId);

    /**
     * Balance = sum(CREDIT) - sum(DEBIT), computed in the database.
     *
     * Because addition is commutative, the result is independent of the order
     * in which transactions were applied — this is WHY out-of-order arrival
     * cannot corrupt balances in this system. COALESCE handles the empty case.
     */
    @Query("""
            select coalesce(sum(case when t.type = com.eventledger.account.domain.TransactionType.CREDIT
                                     then t.amount else -t.amount end), 0)
            from AccountTransaction t
            where t.accountId = :accountId
            """)
    BigDecimal balanceFor(@Param("accountId") String accountId);

    List<AccountTransaction> findByAccountIdOrderByEventTimestampDesc(String accountId, Pageable pageable);
}

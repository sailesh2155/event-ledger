package com.eventledger.account.api;

import com.eventledger.account.api.dto.AccountDetailsResponse;
import com.eventledger.account.api.dto.BalanceResponse;
import com.eventledger.account.api.dto.TransactionRequest;
import com.eventledger.account.api.dto.TransactionResponse;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/accounts/{accountId}")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 201 Created – transaction applied for the first time.
     * 200 OK      – idempotent replay; body is the original application.
     * 409         – same eventId with different details (via exception handler).
     */
    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> apply(@PathVariable String accountId,
                                                     @Valid @RequestBody TransactionRequest request) {
        AccountService.ApplicationResult result = accountService.apply(accountId, request);
        HttpStatus status = result.applied() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TransactionResponse.from(result.transaction()));
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return new BalanceResponse(
                accountId,
                accountService.balanceFor(accountId),
                accountService.transactionCountFor(accountId),
                Instant.now()
        );
    }

    @GetMapping
    public AccountDetailsResponse details(@PathVariable String accountId) {
        List<TransactionResponse> recent = accountService.recentTransactionsFor(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
        return new AccountDetailsResponse(
                accountId,
                accountService.balanceFor(accountId),
                accountService.transactionCountFor(accountId),
                recent
        );
    }
}

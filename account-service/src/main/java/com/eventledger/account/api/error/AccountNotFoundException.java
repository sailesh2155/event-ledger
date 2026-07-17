package com.eventledger.account.api.error;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("No account found with accountId '" + accountId + "' (no transactions recorded)");
    }
}

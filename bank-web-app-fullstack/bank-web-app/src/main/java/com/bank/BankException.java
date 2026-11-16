package com.bank;

/** Business-rule violation (bad input, insufficient funds, unknown account...). Mapped to HTTP 400. */
public class BankException extends RuntimeException {
    public BankException(String message) {
        super(message);
    }
}

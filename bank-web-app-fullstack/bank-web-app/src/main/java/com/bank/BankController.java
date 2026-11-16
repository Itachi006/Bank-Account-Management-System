package com.bank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class BankController {

    public record CreateAccountRequest(String holderName, String pin, BigDecimal initialDeposit) {}
    public record LoginRequest(Long accountId, String pin) {}
    public record AmountRequest(BigDecimal amount) {}
    public record TransferRequest(Long toAccountId, BigDecimal amount) {}

    private final BankService bank;
    /** token -> accountId (in-memory login sessions). */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public BankController(BankService bank) {
        this.bank = bank;
    }

    private long requireSession(String header) {
        if (header != null && header.startsWith("Bearer ")) {
            Long id = sessions.get(header.substring(7));
            if (id != null) return id;
        }
        throw new UnauthorizedException();
    }

    static class UnauthorizedException extends RuntimeException {}

    // ----- public -----

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateAccountRequest r) {
        long id = bank.createAccount(r.holderName(), r.pin(), r.initialDeposit());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("accountId", id, "message", "Account created. Your account number is " + id));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest r) {
        if (r.accountId() == null) throw new BankException("Account number is required.");
        String name = bank.authenticate(r.accountId(), r.pin());
        String token = UUID.randomUUID().toString();
        sessions.put(token, r.accountId());
        names.put(token, name);
        return Map.of("token", token, "accountId", r.accountId(), "holderName", name);
    }

    // ----- authenticated -----

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            sessions.remove(auth.substring(7));
            names.remove(auth.substring(7));
        }
        return Map.of("message", "Logged out.");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        long id = requireSession(auth);
        return Map.of("accountId", id,
                "holderName", names.get(auth.substring(7)),
                "balance", bank.getBalance(id));
    }

    @PostMapping("/me/deposit")
    public Map<String, Object> deposit(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestBody AmountRequest r) {
        long id = requireSession(auth);
        return Map.of("balance", bank.deposit(id, r.amount()), "message", "Deposit successful.");
    }

    @PostMapping("/me/withdraw")
    public Map<String, Object> withdraw(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody AmountRequest r) {
        long id = requireSession(auth);
        return Map.of("balance", bank.withdraw(id, r.amount()), "message", "Withdrawal successful.");
    }

    @PostMapping("/me/transfer")
    public Map<String, Object> transfer(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody TransferRequest r) {
        long id = requireSession(auth);
        if (r.toAccountId() == null) throw new BankException("Recipient account number is required.");
        return Map.of("balance", bank.transferFunds(id, r.toAccountId(), r.amount()),
                "message", "Transfer successful.");
    }

    @GetMapping("/me/transactions")
    public List<BankService.Txn> history(@RequestHeader(value = "Authorization", required = false) String auth) {
        return bank.getTransactionHistory(requireSession(auth));
    }

    // ----- error mapping -----

    @ExceptionHandler(BankException.class)
    public ResponseEntity<Map<String, String>> handleBank(BankException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in again."));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleBadJson(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid input. Please check the values entered."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleDb(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Server error. Please try again."));
    }
}

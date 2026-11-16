package com.bank;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic + JDBC data access.
 * Every multi-step operation: auto-commit off, commit on success, rollback on failure.
 * Balances use BigDecimal / DECIMAL(12,2). All SQL is parameterised.
 */
@Service
public class BankService {

    public record Txn(long txnId, String type, BigDecimal amount, String description, String timestamp) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private final DataSource dataSource;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public BankService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---------- transaction helper ----------

    private <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
    }

    // ---------- validation ----------

    private static BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BankException("Amount must be greater than zero.");
        }
        if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new BankException("Amount can have at most 2 decimal places.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BankException("Amount is too large.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- account creation & login ----------

    public long createAccount(String holderName, String pin, BigDecimal initialDeposit) {
        if (holderName == null || holderName.isBlank() || holderName.length() > 100) {
            throw new BankException("Account holder name is required (max 100 characters).");
        }
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new BankException("PIN must be exactly 4 digits.");
        }
        BigDecimal initial = BigDecimal.ZERO.setScale(2);
        if (initialDeposit != null && initialDeposit.signum() != 0) {
            if (initialDeposit.signum() < 0) {
                throw new BankException("Initial deposit cannot be negative.");
            }
            initial = checkAmount(initialDeposit);
        }
        final BigDecimal start = initial;
        final String hash = encoder.encode(pin);

        return inTransaction(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (holder_name, pin_hash, balance) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, holderName.trim());
                ps.setString(2, hash);
                ps.setBigDecimal(3, start);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No account id returned.");
                    id = keys.getLong(1);
                }
            }
            if (start.signum() > 0) {
                logTransaction(c, id, "DEPOSIT", start, "Initial account deposit");
            }
            return id;
        });
    }

    /** Returns the holder's name if id + PIN match, otherwise throws. */
    public String authenticate(long accountId, String pin) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT holder_name, pin_hash FROM accounts WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && pin != null && encoder.matches(pin, rs.getString("pin_hash"))) {
                    return rs.getString("holder_name");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
        throw new BankException("Invalid account number or PIN.");
    }

    // ---------- balance ----------

    public BigDecimal getBalance(long accountId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
        throw new BankException("Account not found.");
    }

    /** Row-level write lock: SELECT ... FOR UPDATE. Returns null if the account does not exist. */
    private BigDecimal getBalanceForUpdate(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM accounts WHERE account_id = ? FOR UPDATE")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    private void updateBalance(Connection c, long accountId, BigDecimal newBalance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = ? WHERE account_id = ?")) {
            ps.setBigDecimal(1, newBalance);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    private void logTransaction(Connection c, long accountId, String type, BigDecimal amount, String desc)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (account_id, txn_type, amount, description) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, accountId);
            ps.setString(2, type);
            ps.setBigDecimal(3, amount);
            ps.setString(4, desc);
            ps.executeUpdate();
        }
    }

    // ---------- deposit / withdraw ----------

    public BigDecimal deposit(long accountId, BigDecimal rawAmount) {
        BigDecimal amount = checkAmount(rawAmount);
        return inTransaction(c -> {
            BigDecimal balance = getBalanceForUpdate(c, accountId);
            if (balance == null) throw new BankException("Account not found.");
            BigDecimal updated = balance.add(amount);
            updateBalance(c, accountId, updated);
            logTransaction(c, accountId, "DEPOSIT", amount, "Cash/Online Deposit");
            return updated;
        });
    }

    public BigDecimal withdraw(long accountId, BigDecimal rawAmount) {
        BigDecimal amount = checkAmount(rawAmount);
        return inTransaction(c -> {
            BigDecimal balance = getBalanceForUpdate(c, accountId);
            if (balance == null) throw new BankException("Account not found.");
            if (balance.compareTo(amount) < 0) {
                throw new BankException("Insufficient funds. Available balance: " + balance.toPlainString());
            }
            BigDecimal updated = balance.subtract(amount);
            updateBalance(c, accountId, updated);
            logTransaction(c, accountId, "WITHDRAWAL", amount, "Cash Withdrawal");
            return updated;
        });
    }

    // ---------- transfer ----------

    public BigDecimal transferFunds(long fromId, long toId, BigDecimal rawAmount) {
        if (fromId == toId) throw new BankException("You cannot transfer money to your own account.");
        BigDecimal amount = checkAmount(rawAmount);
        return inTransaction(c -> {
            // Lock rows in a consistent order (lower id first) so two opposite transfers can't deadlock.
            long first = Math.min(fromId, toId);
            long second = Math.max(fromId, toId);
            BigDecimal firstBal = getBalanceForUpdate(c, first);
            BigDecimal secondBal = getBalanceForUpdate(c, second);
            BigDecimal fromBal = (first == fromId) ? firstBal : secondBal;
            BigDecimal toBal = (first == toId) ? firstBal : secondBal;

            if (fromBal == null) throw new BankException("Sender account not found.");
            if (toBal == null) throw new BankException("Recipient account #" + toId + " does not exist.");
            if (fromBal.compareTo(amount) < 0) {
                throw new BankException("Insufficient funds. Available balance: " + fromBal.toPlainString());
            }

            BigDecimal newFrom = fromBal.subtract(amount);
            updateBalance(c, fromId, newFrom);
            updateBalance(c, toId, toBal.add(amount));
            logTransaction(c, fromId, "TRANSFER_OUT", amount, "Transfer to Account #" + toId);
            logTransaction(c, toId, "TRANSFER_IN", amount, "Transfer from Account #" + fromId);
            return newFrom;
        });
    }

    // ---------- history ----------

    public List<Txn> getTransactionHistory(long accountId) {
        List<Txn> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT txn_id, txn_type, amount, description, txn_timestamp FROM transactions " +
                             "WHERE account_id = ? ORDER BY txn_timestamp DESC, txn_id DESC")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Txn(
                            rs.getLong("txn_id"),
                            rs.getString("txn_type"),
                            rs.getBigDecimal("amount"),
                            rs.getString("description"),
                            rs.getTimestamp("txn_timestamp").toLocalDateTime().toString().replace('T', ' ')));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
        return list;
    }
}

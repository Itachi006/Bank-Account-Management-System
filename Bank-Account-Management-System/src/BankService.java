import java.sql.*;
import java.math.BigDecimal;

public class BankService {

    public int createAccount(String holderName, String pin, BigDecimal initialDeposit) {
        if (initialDeposit.compareTo(BigDecimal.ZERO) < 0) {
            System.out.println("Error: Initial deposit cannot be negative.");
            return -1;
        }
        String insertAccount = "INSERT INTO accounts (holder_name, pin, balance) VALUES (?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertAccount, Statement.RETURN_GENERATED_KEYS)) {
            
            conn.setAutoCommit(false);
            pstmt.setString(1, holderName);
            pstmt.setString(2, pin);
            pstmt.setBigDecimal(3, initialDeposit);
            pstmt.executeUpdate();

            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                int accountId = rs.getInt(1);
                if (initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
                    logTransaction(conn, accountId, "DEPOSIT", initialDeposit, "Initial account deposit");
                }
                conn.commit();
                return accountId;
            }
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("Database error while creating account: " + e.getMessage());
        }
        return -1;
    }

    public boolean authenticate(int accountId, String pin) {
        String query = "SELECT pin FROM accounts WHERE account_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setInt(1, accountId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("pin").equals(pin);
            }
        } catch (SQLException e) {
            System.out.println("Authentication error: " + e.getMessage());
        }
        return false;
    }

    public BigDecimal getBalance(int accountId) {
        String query = "SELECT balance FROM accounts WHERE account_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setInt(1, accountId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getBigDecimal("balance");
            }
        } catch (SQLException e) {
            System.out.println("Error fetching balance: " + e.getMessage());
        }
        return null;
    }

    public boolean deposit(int accountId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Error: Deposit amount must be greater than zero.");
            return false;
        }
        String updateSql = "UPDATE accounts SET balance = balance + ? WHERE account_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            conn.setAutoCommit(false);
            pstmt.setBigDecimal(1, amount);
            pstmt.setInt(2, accountId);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                logTransaction(conn, accountId, "DEPOSIT", amount, "Cash/Online Deposit");
                conn.commit();
                return true;
            }
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("Deposit failed: " + e.getMessage());
        }
        return false;
    }

    public boolean withdraw(int accountId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Error: Withdrawal amount must be greater than zero.");
            return false;
        }
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            BigDecimal currentBalance = getBalanceForUpdate(conn, accountId);
            if (currentBalance == null || currentBalance.compareTo(amount) < 0) {
                System.out.println("Error: Insufficient funds.");
                conn.rollback();
                return false;
            }
            String updateSql = "UPDATE accounts SET balance = balance - ? WHERE account_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                pstmt.setBigDecimal(1, amount);
                pstmt.setInt(2, accountId);
                pstmt.executeUpdate();
            }
            logTransaction(conn, accountId, "WITHDRAWAL", amount, "Account Withdrawal");
            conn.commit();
            return true;
        } catch (SQLException e) {
            System.out.println("Withdrawal failed: " + e.getMessage());
        }
        return false;
    }

    public boolean transferFunds(int fromAccountId, int toAccountId, BigDecimal amount) {
        if (fromAccountId == toAccountId) {
            System.out.println("Error: Cannot transfer to the same account.");
            return false;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("Error: Transfer amount must be greater than zero.");
            return false;
        }
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);

            BigDecimal senderBalance = getBalanceForUpdate(conn, fromAccountId);
            BigDecimal receiverBalance = getBalanceForUpdate(conn, toAccountId);

            if (receiverBalance == null) {
                System.out.println("Error: Recipient account does not exist.");
                conn.rollback();
                return false;
            }
            if (senderBalance == null || senderBalance.compareTo(amount) < 0) {
                System.out.println("Error: Insufficient funds for transfer.");
                conn.rollback();
                return false;
            }

            String deductSql = "UPDATE accounts SET balance = balance - ? WHERE account_id = ?";
            String creditSql = "UPDATE accounts SET balance = balance + ? WHERE account_id = ?";

            try (PreparedStatement deductStmt = conn.prepareStatement(deductSql);
                 PreparedStatement creditStmt = conn.prepareStatement(creditSql)) {
                deductStmt.setBigDecimal(1, amount);
                deductStmt.setInt(2, fromAccountId);
                deductStmt.executeUpdate();

                creditStmt.setBigDecimal(1, amount);
                creditStmt.setInt(2, toAccountId);
                creditStmt.executeUpdate();
            }

            logTransaction(conn, fromAccountId, "TRANSFER_OUT", amount, "Transfer to Account #" + toAccountId);
            logTransaction(conn, toAccountId, "TRANSFER_IN", amount, "Transfer from Account #" + fromAccountId);

            conn.commit();
            return true;
        } catch (SQLException e) {
            System.out.println("Fund transfer failed: " + e.getMessage());
        }
        return false;
    }

    public void printTransactionHistory(int accountId) {
        String query = "SELECT txn_id, txn_type, amount, description, txn_timestamp FROM transactions WHERE account_id = ? ORDER BY txn_timestamp DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setInt(1, accountId);
            ResultSet rs = pstmt.executeQuery();
            System.out.println("\n--- Transaction History (Account #" + accountId + ") ---");
            boolean hasRecords = false;
            while (rs.next()) {
                hasRecords = true;
                System.out.printf("ID: %d | %-12s | $%.2f | %s | %s%n",
                        rs.getInt("txn_id"),
                        rs.getString("txn_type"),
                        rs.getBigDecimal("amount"),
                        rs.getString("description"),
                        rs.getTimestamp("txn_timestamp"));
            }
            if (!hasRecords) {
                System.out.println("No transactions found.");
            }
        } catch (SQLException e) {
            System.out.println("Error fetching transaction history: " + e.getMessage());
        }
    }

    private BigDecimal getBalanceForUpdate(Connection conn, int accountId) throws SQLException {
        String query = "SELECT balance FROM accounts WHERE account_id = ? FOR UPDATE";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setInt(1, accountId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getBigDecimal("balance");
            }
        }
        return null;
    }

    private void logTransaction(Connection conn, int accountId, String type, BigDecimal amount, String description) throws SQLException {
        String insertTxn = "INSERT INTO transactions (account_id, txn_type, amount, description) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertTxn)) {
            pstmt.setInt(1, accountId);
            pstmt.setString(2, type);
            pstmt.setBigDecimal(3, amount);
            pstmt.setString(4, description);
            pstmt.executeUpdate();
        }
    }
}

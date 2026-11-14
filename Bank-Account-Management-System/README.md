# Bank Account Management & Transaction System

A relational database-driven banking application built using **Java (JDBC)** and **MySQL**. Designed to simulate core banking workflows with strict transaction atomicity, input validation, and audit logging.

## Key Features
- **Account Management:** Create new customer accounts with secure PIN authentication.
- **Core Banking Operations:** Supports balance inquiries, cash/online deposits, and validated withdrawals (prevents negative balances and overdrafts).
- **Atomic Fund Transfers:** Uses JDBC transaction management (`setAutoCommit(false)`, `commit()`, `rollback()`) and row-level locking (`SELECT ... FOR UPDATE`) to guarantee funds are never lost or duplicated during peer-to-peer transfers.
- **Audit Trail & Ledger:** Automatically records every transaction (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER_IN`, `TRANSFER_OUT`) with timestamps in a separate relational ledger table.

## Database Schema (`banking_db.sql`)
- `accounts`: Stores `account_id`, `holder_name`, `pin`, `balance`, and `created_at`.
- `transactions`: Stores `txn_id`, `account_id` (Foreign Key), `txn_type`, `amount`, `description`, and `txn_timestamp`.

## Setup & Execution
1. **Initialize the Database:**
   Run `banking_db.sql` in your MySQL Workbench or CLI to create the `banking_db` database and tables.
2. **Configure Database Credentials:**
   Update `URL`, `USER`, and `PASSWORD` in `src/DBConnection.java`.
3. **Compile & Run (with MySQL Connector/J in classpath):**
   ```bash
   javac -cp ".:mysql-connector-j.jar" src/*.java
   java -cp ".:mysql-connector-j.jar:src" Main
   ```

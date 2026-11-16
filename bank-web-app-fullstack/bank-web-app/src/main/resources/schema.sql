-- Works on both MySQL 8 and H2 (MySQL mode). Runs automatically at startup.
CREATE TABLE IF NOT EXISTS accounts (
    account_id  INT PRIMARY KEY AUTO_INCREMENT,
    holder_name VARCHAR(100) NOT NULL,
    pin_hash    VARCHAR(100) NOT NULL,
    balance     DECIMAL(12, 2) DEFAULT 0.00,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
    txn_id        INT PRIMARY KEY AUTO_INCREMENT,
    account_id    INT NOT NULL,
    txn_type      ENUM('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT') NOT NULL,
    amount        DECIMAL(12, 2) NOT NULL,
    description   VARCHAR(255),
    txn_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
);

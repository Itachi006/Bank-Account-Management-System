import java.math.BigDecimal;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        BankService bankService = new BankService();

        while (true) {
            System.out.println("\n=== BANK ACCOUNT MANAGEMENT SYSTEM ===");
            System.out.println("1. Create New Account");
            System.out.println("2. Login to Existing Account");
            System.out.println("3. Exit");
            System.out.print("Select an option: ");

            int choice = scanner.nextInt();
            scanner.nextLine();

            if (choice == 1) {
                System.out.print("Enter Account Holder Name: ");
                String name = scanner.nextLine();
                System.out.print("Set a 4-digit PIN: ");
                String pin = scanner.nextLine();
                System.out.print("Enter Initial Deposit Amount: ");
                BigDecimal deposit = scanner.nextBigDecimal();

                int newId = bankService.createAccount(name, pin, deposit);
                if (newId != -1) {
                    System.out.println("Account created successfully! Your Account ID is: " + newId);
                }
            } else if (choice == 2) {
                System.out.print("Enter Account ID: ");
                int accId = scanner.nextInt();
                scanner.nextLine();
                System.out.print("Enter PIN: ");
                String pin = scanner.nextLine();

                if (bankService.authenticate(accId, pin)) {
                    System.out.println("Login successful!");
                    handleAccountSession(scanner, bankService, accId);
                } else {
                    System.out.println("Invalid Account ID or PIN.");
                }
            } else if (choice == 3) {
                System.out.println("Exiting system. Goodbye!");
                break;
            } else {
                System.out.println("Invalid option. Please try again.");
            }
        }
        scanner.close();
    }

    private static void handleAccountSession(Scanner scanner, BankService bankService, int accountId) {
        while (true) {
            System.out.println("\n--- Account Menu (ID: " + accountId + ") ---");
            System.out.println("1. Check Balance");
            System.out.println("2. Deposit Funds");
            System.out.println("3. Withdraw Funds");
            System.out.println("4. Transfer Funds");
            System.out.println("5. View Transaction History");
            System.out.println("6. Logout");
            System.out.print("Choose an action: ");

            int action = scanner.nextInt();
            if (action == 1) {
                BigDecimal balance = bankService.getBalance(accountId);
                System.out.printf("Current Balance: $%.2f%n", balance);
            } else if (action == 2) {
                System.out.print("Enter deposit amount: ");
                BigDecimal amount = scanner.nextBigDecimal();
                if (bankService.deposit(accountId, amount)) {
                    System.out.println("Deposit successful!");
                }
            } else if (action == 3) {
                System.out.print("Enter withdrawal amount: ");
                BigDecimal amount = scanner.nextBigDecimal();
                if (bankService.withdraw(accountId, amount)) {
                    System.out.println("Withdrawal successful!");
                }
            } else if (action == 4) {
                System.out.print("Enter Recipient Account ID: ");
                int toId = scanner.nextInt();
                System.out.print("Enter transfer amount: ");
                BigDecimal amount = scanner.nextBigDecimal();
                if (bankService.transferFunds(accountId, toId, amount)) {
                    System.out.println("Transfer completed successfully!");
                }
            } else if (action == 5) {
                bankService.printTransactionHistory(accountId);
            } else if (action == 6) {
                System.out.println("Logged out.");
                break;
            } else {
                System.out.println("Invalid choice.");
            }
        }
    }
}

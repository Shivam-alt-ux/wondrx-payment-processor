# WONDRx Java Backend Intern Assignment
## Idempotent Payment/Wallet Event Processor

An internal transaction ledger service that safely absorbs duplicate
webhook retries and prevents concurrent debits from ever driving a
wallet balance negative.

## Stack

- Java 17
- Spring Boot 3.3 (Web, Data JPA, Validation)
- H2 in-memory database (zero external setup)
- JUnit 5 + Spring Boot Test + TestRestTemplate

## Running it

No Postman, no Docker, no external DB. Two ways to run it:

**Run the tests (this is how it will be graded):**
Open the project in IntelliJ as a Maven project and run
`src/test/java/com/wondrx/ledger/TransactionProcessorIntegrationTest.java`
directly (right-click → Run). It spins up a real embedded server and an
in-memory H2 database per run — nothing to configure.

Or from the command line:
```
mvn test
```

**Run the app itself:**
```
mvn spring-boot:run
```
Then, for manual poking around:
```
# create a wallet
curl -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"userId":"11111111-1111-1111-1111-111111111111","initialBalance":500.00}'

# send a webhook
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"22222222-2222-2222-2222-222222222222","userId":"11111111-1111-1111-1111-111111111111","amount":250.00,"type":"DEBIT"}'
```

## API

### `POST /api/v1/transactions/process`

```json
{
  "transactionId": "UUID",
  "userId": "UUID",
  "amount": 250.00,
  "type": "DEBIT"
}
```

| Outcome | HTTP Status | Notes |
|---|---|---|
| First time seeing this `transactionId`, funds sufficient | `200 OK` | Balance debited |
| First time seeing this `transactionId`, funds insufficient | `402 Payment Required` | Balance untouched |
| `transactionId` already seen (duplicate/retry) | `409 Conflict` | Cached original result returned in the body, `duplicate: true` |

### `POST /api/v1/wallets` and `GET /api/v1/wallets/{userId}`

Convenience endpoints for seeding/inspecting a balance during manual
testing — not part of the core assignment.

## Design at a glance

- **Idempotency** is enforced by the database, not application logic:
  `transaction_id` is the **primary key** of `transaction_records`. Every
  incoming request first tries to `INSERT` a row with that id in its own
  short-lived transaction (`IdempotencyGuardService`). Exactly one
  concurrent insert can win; the others get a constraint violation and are
  told to go read the winner's cached result. See `DECISIONS.md` for the
  full reasoning, including a subtlety that required correcting an
  AI-assisted first draft.

- **Negative-balance prevention** is enforced with a database-level
  pessimistic lock: `WalletRepository#findByUserIdForUpdate` issues a
  `SELECT ... FOR UPDATE`, held for the duration of the balance
  check-and-update inside `WalletDebitService`. Concurrent debits against
  the *same* wallet are serialized by the database itself.

See `DECISIONS.md` for the two required write-ups.

# DECISIONS.md

## 1. How did you handle the concurrency race condition?

There are two distinct races in this problem, and they're handled with two
different mechanisms:

**A. Duplicate `transactionId` (idempotency race).**
`transaction_id` is the **primary key** of the `transaction_records` table.
When a request comes in, the very first thing that happens
(`IdempotencyGuardService.reserve`) is an attempt to `INSERT` a row for
that id, in its own short `REQUIRES_NEW` transaction. The database's
unique index is the actual mutual-exclusion mechanism, not application
code: if three threads race to insert the same primary key, the database
lets exactly one `INSERT` through and blocks the others on the index's row
lock until the winner commits — at which point they fail with a
constraint violation instead of blocking forever. The two "losers" catch
that violation, and re-read the now-committed winner's row to return its
cached result (as `409 Conflict`). Only the winner is allowed to go on and
touch the wallet, so the balance can only ever be moved once per
`transactionId`, no matter how many duplicates land within the retry
window.

**B. Concurrent debits against the same wallet (negative-balance race).**
This is solved with a classic pessimistic lock:
`WalletRepository.findByUserIdForUpdate` issues `SELECT ... FOR UPDATE`
against the wallet row. That lock is held for the *entire*
check-then-update (`WalletDebitService.applyDebitAndFinalize`) inside one
`REQUIRES_NEW` transaction, and is only released when that transaction
commits. So if 10 debits hit the same wallet concurrently, the database
itself serializes them one at a time — each one sees the balance left by
the one before it, not a stale read from before that debit was applied.
That's what guarantees the balance can never go negative and never "loses"
a debit to a race, even though the requests themselves are genuinely
concurrent at the HTTP layer.

I deliberately did **not** rely on optimistic locking (`@Version` +
retry) for the wallet, even though it's a common alternative, because the
assignment explicitly calls out ten simultaneous debits against a small
balance — that's a high-contention hot row, and optimistic locking would
mean most of those 10 requests fail-and-retry multiple times, which adds
latency and complexity for no benefit here. Pessimistic locking is the
simpler, more predictable choice when contention on one row is expected
and the critical section (a balance check-and-update) is short.

## 2. Where did your AI assistant give an incorrect or sub-optimal suggestion?

The first draft of the service layer put everything — the idempotency
`INSERT` attempt *and* the wallet debit — inside a single `@Transactional`
method, something like:

```java
@Transactional
public Response process(request) {
    try {
        transactionRecordRepository.saveAndFlush(newRecord);
    } catch (DataIntegrityViolationException e) {
        // fetch existing record and return 409
        return conflictResponse(transactionRecordRepository.findById(...));
    }
    // ... proceed to debit wallet ...
}
```

This looks reasonable but is subtly broken: once Hibernate's persistence
context throws a `DataIntegrityViolationException` from a flush, that
context (and the surrounding transaction) is considered unusable for the
rest of its lifetime — any further use of the same `EntityManager`,
including the "just fetch the existing record" read in the `catch` block,
either throws a fresh exception or, worse, silently misbehaves depending
on the Hibernate/driver version. It also meant the transaction was marked
rollback-only by Spring's default exception handling, so the "duplicate"
branch could end up trying to read inside a transaction that was already
doomed to roll back.

**Fix:** split the reservation attempt into its own service
(`IdempotencyGuardService`) with `@Transactional(propagation =
REQUIRES_NEW)`. That gives it a completely separate transaction/
persistence context, so when it fails and rolls back, it doesn't poison
anything else — the orchestrating method continues in a clean state and
does a fresh read for the conflict case. The wallet debit
(`WalletDebitService`) got the same `REQUIRES_NEW` treatment for a related
reason: it needs to hold a row lock for the full duration of the
check-and-update, and keeping it as its own transaction boundary (rather
than nested inside the caller's) makes exactly when the lock is acquired
and released unambiguous, rather than depending on where the outer
transaction happens to start and end.

The other place worth flagging: an early suggestion used Spring's default
self-invocation pattern — i.e., one `@Service` class calling its own
`@Transactional` methods via `this.someMethod()`. That silently does
**not** go through the Spring AOP proxy, so the `@Transactional` /
`REQUIRES_NEW` annotations are simply ignored on self-invocation, and
everything quietly runs in whatever transaction (or lack of one) the
outer call started in — no error, just wrong behavior that would only
show up under concurrency, exactly the kind of bug that's easy to miss by
reading the code and easy to hit in production. The fix was structural:
`IdempotencyGuardService` and `WalletDebitService` are separate Spring
beans injected into `TransactionService`, so every call between them goes
through the real proxy and the transaction boundaries are honored.

package com.wondrx.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency ledger.
 *
 * transactionId is the PRIMARY KEY. That is the entire idempotency
 * mechanism: the database itself refuses a second row with the same id,
 * so "has this transactionId already been seen" is never a race-prone
 * read-then-write check in application code - it is enforced by the
 * unique index at INSERT time. See TransactionService#tryReserve.
 */
@Entity
@Table(name = "transaction_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "response_message")
    private String responseMessage;

    @Column(name = "resulting_balance", precision = 19, scale = 2)
    private BigDecimal resultingBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TransactionRecord(UUID transactionId, UUID userId, BigDecimal amount, TransactionType type) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PROCESSING;
        this.createdAt = Instant.now();
    }
}

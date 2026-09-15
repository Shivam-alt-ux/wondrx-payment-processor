package com.wondrx.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents a user's wallet balance.
 *
 * Concurrency note: rows in this table are read with a PESSIMISTIC_WRITE
 * lock (SELECT ... FOR UPDATE) whenever a debit/credit is applied, so that
 * concurrent transactions against the same wallet are serialized at the
 * database level and the balance can never go negative. See
 * WalletRepository#findByUserIdForUpdate and TransactionService#applyDebit.
 */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    public Wallet(UUID userId, BigDecimal balance) {
        this.userId = userId;
        this.balance = balance;
    }
}

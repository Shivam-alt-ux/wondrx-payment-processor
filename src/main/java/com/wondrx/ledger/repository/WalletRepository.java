package com.wondrx.ledger.repository;

import com.wondrx.ledger.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * SELECT ... FOR UPDATE equivalent. Issued inside the caller's
     * transaction, this blocks any other transaction from reading (with a
     * lock) or writing the same wallet row until the current transaction
     * commits or rolls back. This is the database-level lock that prevents
     * two concurrent debits from both reading a stale balance and driving
     * it negative.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
}

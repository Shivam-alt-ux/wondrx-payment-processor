package com.wondrx.ledger.service;

import com.wondrx.ledger.model.TransactionRecord;
import com.wondrx.ledger.model.TransactionStatus;
import com.wondrx.ledger.model.Wallet;
import com.wondrx.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Only called once IdempotencyGuardService has confirmed the caller "owns"
 * this transactionId, so no two threads ever run this for the same
 * transactionId concurrently. Multiple DIFFERENT transactionIds against the
 * SAME wallet, however, absolutely can arrive concurrently - that is what
 * findByUserIdForUpdate() (SELECT ... FOR UPDATE) guards against.
 *
 * The lock is acquired, the balance is checked, the balance is (maybe)
 * updated, and the ledger row is finalized - all inside one transaction.
 * The row lock on wallets is only released when this transaction commits,
 * so a second concurrent debit against the same wallet is forced to wait
 * until this one is fully done, then sees the updated balance. That
 * serialization is what makes "10 concurrent debits, balance never goes
 * negative" hold true.
 */
@Service
public class WalletDebitService {

    private final WalletRepository walletRepository;

    public WalletDebitService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionRecord applyDebitAndFinalize(TransactionRecord record) {
        UUID userId = record.getUserId();
        BigDecimal amount = record.getAmount();

        // Blocks here until any other transaction holding this wallet's
        // row lock commits or rolls back.
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("No wallet found for userId=" + userId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            record.setStatus(TransactionStatus.FAILED);
            record.setResponseMessage("Insufficient funds");
            record.setResultingBalance(wallet.getBalance());
            return record;
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        record.setStatus(TransactionStatus.SUCCESS);
        record.setResponseMessage("Processed successfully");
        record.setResultingBalance(wallet.getBalance());
        return record;
    }
}

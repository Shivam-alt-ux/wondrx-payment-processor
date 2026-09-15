package com.wondrx.ledger.service;

import com.wondrx.ledger.model.TransactionRecord;
import com.wondrx.ledger.repository.TransactionRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns exactly one job: try to be the FIRST row with this transactionId.
 *
 * This runs in its own REQUIRES_NEW transaction, deliberately separate
 * from the orchestrating TransactionService method. Two reasons:
 *
 *  1. If saveAndFlush() throws a DataIntegrityViolationException, Hibernate
 *     marks that persistence context / transaction as unusable. If this
 *     logic lived in the SAME transaction as the rest of the request, the
 *     "loser" thread would have no clean transaction left to fetch the
 *     winner's cached record from - it would have to abort the whole
 *     request instead of gracefully returning the cached result. Giving
 *     the reservation attempt its own transaction means only THIS small
 *     transaction is poisoned and rolled back; the caller continues in a
 *     fresh transaction to read the winner's result.
 *
 *  2. It commits (or rolls back) immediately, releasing the unique-index
 *     row lock on transaction_records as fast as possible instead of
 *     holding it for the entire duration of the wallet debit.
 */
@Service
public class IdempotencyGuardService {

    private final TransactionRecordRepository transactionRecordRepository;

    public IdempotencyGuardService(TransactionRecordRepository transactionRecordRepository) {
        this.transactionRecordRepository = transactionRecordRepository;
    }

    /**
     * @return true if this call is the first (and only) one to successfully
     * insert the given transactionId - i.e. this caller "won" and should go
     * on to actually move money. false means someone else already owns
     * this transactionId; the caller should look up the existing record
     * instead of touching the wallet.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(TransactionRecord record) {
        try {
            transactionRecordRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}

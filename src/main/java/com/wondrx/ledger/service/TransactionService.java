package com.wondrx.ledger.service;

import com.wondrx.ledger.dto.TransactionRequest;
import com.wondrx.ledger.dto.TransactionResponse;
import com.wondrx.ledger.model.TransactionRecord;
import com.wondrx.ledger.model.TransactionStatus;
import com.wondrx.ledger.repository.TransactionRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final IdempotencyGuardService idempotencyGuardService;
    private final WalletDebitService walletDebitService;
    private final TransactionRecordRepository transactionRecordRepository;

    public TransactionService(IdempotencyGuardService idempotencyGuardService,
                               WalletDebitService walletDebitService,
                               TransactionRecordRepository transactionRecordRepository) {
        this.idempotencyGuardService = idempotencyGuardService;
        this.walletDebitService = walletDebitService;
        this.transactionRecordRepository = transactionRecordRepository;
    }

    public ProcessOutcome process(TransactionRequest request) {
        TransactionRecord candidate = new TransactionRecord(
                request.getTransactionId(),
                request.getUserId(),
                request.getAmount(),
                request.getType()
        );

        boolean wonReservation = idempotencyGuardService.reserve(candidate);

        if (!wonReservation) {
            // Someone else already owns this transactionId. Read their
            // (by now committed) result back and hand it to this caller
            // instead of touching the wallet a second time.
            TransactionRecord existing = transactionRecordRepository.findById(request.getTransactionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Reservation lost but no existing record found for transactionId="
                                    + request.getTransactionId()));

            TransactionResponse body = new TransactionResponse(
                    existing.getTransactionId(),
                    existing.getStatus(),
                    "Duplicate request: transactionId already processed. Returning cached result. "
                            + existing.getResponseMessage(),
                    existing.getResultingBalance(),
                    true
            );
            return new ProcessOutcome(body, HttpStatus.CONFLICT);
        }

        TransactionRecord finalized = walletDebitService.applyDebitAndFinalize(candidate);

        TransactionResponse body = new TransactionResponse(
                finalized.getTransactionId(),
                finalized.getStatus(),
                finalized.getResponseMessage(),
                finalized.getResultingBalance(),
                false
        );

        HttpStatus status = finalized.getStatus() == TransactionStatus.SUCCESS
                ? HttpStatus.OK
                : HttpStatus.PAYMENT_REQUIRED;

        return new ProcessOutcome(body, status);
    }
}

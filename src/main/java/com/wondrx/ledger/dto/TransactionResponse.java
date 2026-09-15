package com.wondrx.ledger.dto;

import com.wondrx.ledger.model.TransactionStatus;

import java.math.BigDecimal;
import java.util.UUID;

public class TransactionResponse {

    private UUID transactionId;
    private TransactionStatus status;
    private String message;
    private BigDecimal resultingBalance;
    private boolean duplicate;

    public TransactionResponse() {
    }

    public TransactionResponse(UUID transactionId, TransactionStatus status, String message,
                                BigDecimal resultingBalance, boolean duplicate) {
        this.transactionId = transactionId;
        this.status = status;
        this.message = message;
        this.resultingBalance = resultingBalance;
        this.duplicate = duplicate;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public BigDecimal getResultingBalance() {
        return resultingBalance;
    }

    public boolean isDuplicate() {
        return duplicate;
    }
}

package com.wondrx.ledger.service;

import com.wondrx.ledger.dto.TransactionResponse;
import org.springframework.http.HttpStatus;

public class ProcessOutcome {

    private final TransactionResponse body;
    private final HttpStatus httpStatus;

    public ProcessOutcome(TransactionResponse body, HttpStatus httpStatus) {
        this.body = body;
        this.httpStatus = httpStatus;
    }

    public TransactionResponse getBody() {
        return body;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}

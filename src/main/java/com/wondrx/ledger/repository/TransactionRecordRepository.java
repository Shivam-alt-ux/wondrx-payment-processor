package com.wondrx.ledger.repository;

import com.wondrx.ledger.model.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {
}

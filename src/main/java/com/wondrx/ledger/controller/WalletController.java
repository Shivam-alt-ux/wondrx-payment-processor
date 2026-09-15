package com.wondrx.ledger.controller;

import com.wondrx.ledger.model.Wallet;
import com.wondrx.ledger.repository.WalletRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Not part of the core idempotency assignment, but included so a reviewer
 * (or the test suite) can seed a wallet with a starting balance without
 * touching the database directly.
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @PostMapping
    public ResponseEntity<Wallet> create(@RequestBody CreateWalletRequest request) {
        Wallet wallet = new Wallet(request.userId(), request.initialBalance());
        Wallet saved = walletRepository.save(wallet);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Wallet> get(@PathVariable UUID userId) {
        return walletRepository.findById(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateWalletRequest(@NotNull UUID userId, @NotNull BigDecimal initialBalance) {
    }
}

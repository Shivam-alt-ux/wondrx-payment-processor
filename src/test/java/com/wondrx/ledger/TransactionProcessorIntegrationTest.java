package com.wondrx.ledger;

import com.wondrx.ledger.dto.TransactionRequest;
import com.wondrx.ledger.dto.TransactionResponse;
import com.wondrx.ledger.model.TransactionStatus;
import com.wondrx.ledger.model.TransactionType;
import com.wondrx.ledger.model.Wallet;
import com.wondrx.ledger.repository.TransactionRecordRepository;
import com.wondrx.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-config integration test suite.
 *
 * Runs against a real embedded Tomcat instance (SpringBootTest.RANDOM_PORT)
 * backed by an in-memory H2 database - no external database, no Postman,
 * no manual setup. Just run this class in IntelliJ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Idempotent Payment/Wallet Event Processor")
class TransactionProcessorIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1/transactions/process";
        // Zero-config isolation between tests: wipe both tables before each test.
        transactionRecordRepository.deleteAll();
        walletRepository.deleteAll();
    }

    private UUID seedWallet(BigDecimal balance) {
        UUID userId = UUID.randomUUID();
        walletRepository.save(new Wallet(userId, balance));
        return userId;
    }

    private ResponseEntity<TransactionResponse> send(TransactionRequest request) {
        return restTemplate.postForEntity(baseUrl, request, TransactionResponse.class);
    }

    // ------------------------------------------------------------------
    // 1. HAPPY PATH
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitTransactionSuccessfully() {
        System.out.println("\n[TEST] Intent: process ONE valid debit and confirm the balance drops by exactly that amount.");

        UUID userId = seedWallet(new BigDecimal("500.00"));
        TransactionRequest request = new TransactionRequest(
                UUID.randomUUID(), userId, new BigDecimal("250.00"), TransactionType.DEBIT);

        ResponseEntity<TransactionResponse> response = send(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(TransactionStatus.SUCCESS, response.getBody().getStatus());
        assertEquals(0, new BigDecimal("250.00").compareTo(response.getBody().getResultingBalance()));

        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(wallet.getBalance()));

        System.out.println("[TEST] Result: PASSED. HTTP " + response.getStatusCode()
                + ", resulting balance = " + wallet.getBalance());
    }

    // ------------------------------------------------------------------
    // 2. IDEMPOTENCY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void sendsThreeIdenticalTransactionIdsSimultaneously() throws Exception {
        System.out.println("\n[TEST] Intent: fire 3 concurrent requests with the SAME transactionId and confirm "
                + "exactly one is treated as new (200) while the other two are rejected as duplicates (409), "
                + "and the wallet is only debited once.");

        BigDecimal startingBalance = new BigDecimal("1000.00");
        BigDecimal debitAmount = new BigDecimal("100.00");
        UUID userId = seedWallet(startingBalance);
        UUID sharedTransactionId = UUID.randomUUID();

        int concurrentRequests = 3;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<TransactionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            tasks.add(() -> {
                TransactionRequest request = new TransactionRequest(
                        sharedTransactionId, userId, debitAmount, TransactionType.DEBIT);
                readyLatch.countDown();
                startLatch.await(); // release all threads at (almost) the same instant
                return send(request);
            });
        }

        List<Future<ResponseEntity<TransactionResponse>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<TransactionResponse>> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await(2, TimeUnit.SECONDS);
        startLatch.countDown(); // go!

        List<ResponseEntity<TransactionResponse>> responses = new ArrayList<>();
        for (Future<ResponseEntity<TransactionResponse>> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        long successCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .count();
        long conflictCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .count();

        assertEquals(1, successCount, "Exactly one of the three identical requests must succeed");
        assertEquals(2, conflictCount, "The other two identical requests must be rejected as duplicates");

        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        BigDecimal expectedBalance = startingBalance.subtract(debitAmount);
        assertEquals(0, expectedBalance.compareTo(wallet.getBalance()),
                "Balance must be debited exactly once, not three times");

        long ledgerRows = transactionRecordRepository.count();
        assertEquals(1, ledgerRows, "Only one ledger row should exist for the shared transactionId");

        System.out.println("[TEST] Result: PASSED. successes=" + successCount + " conflicts=" + conflictCount
                + " finalBalance=" + wallet.getBalance() + " (expected " + expectedBalance + ")");
    }

    // ------------------------------------------------------------------
    // 3. RACE CONDITION
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. "
            + "Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void sendsTenConcurrentDebitsAgainstFiveHundredBalance() throws Exception {
        System.out.println("\n[TEST] Intent: fire 10 concurrent DIFFERENT-transactionId debits of Rs.100 each "
                + "against a Rs.500 wallet. Exactly 5 must succeed, 5 must fail with insufficient funds, "
                + "and the balance must never go negative - final balance must be exactly Rs.0.");

        BigDecimal startingBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("100.00");
        UUID userId = seedWallet(startingBalance);

        int concurrentRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<TransactionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            tasks.add(() -> {
                TransactionRequest request = new TransactionRequest(
                        UUID.randomUUID(), userId, debitAmount, TransactionType.DEBIT);
                readyLatch.countDown();
                startLatch.await();
                return send(request);
            });
        }

        List<Future<ResponseEntity<TransactionResponse>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<TransactionResponse>> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await(2, TimeUnit.SECONDS);
        startLatch.countDown();

        List<ResponseEntity<TransactionResponse>> responses = new ArrayList<>();
        for (Future<ResponseEntity<TransactionResponse>> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        long successCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .count();
        long insufficientFundsCount = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.PAYMENT_REQUIRED)
                .count();

        List<String> statuses = responses.stream()
                .map(r -> r.getStatusCode().toString())
                .collect(Collectors.toList());
        System.out.println("[TEST] Individual response codes: " + statuses);

        assertEquals(5, successCount, "Exactly 5 of the 10 concurrent debits should succeed");
        assertEquals(5, insufficientFundsCount, "Exactly 5 of the 10 concurrent debits should fail with insufficient funds");

        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.getBalance()),
                "Final balance must be exactly zero - never negative, never left with untouched funds");
        assertTrue(wallet.getBalance().compareTo(BigDecimal.ZERO) >= 0, "Balance must never go negative");

        System.out.println("[TEST] Result: PASSED. successes=" + successCount
                + " insufficientFunds=" + insufficientFundsCount + " finalBalance=" + wallet.getBalance());
    }
}

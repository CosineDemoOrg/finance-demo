/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.transactionhistory;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for the TransactionHistory service.
 *
 * Functions to show the transaction history for each user account.
 */
@RestController
public final class TransactionHistoryController {

    private static final Logger LOGGER =
        LogManager.getLogger(TransactionHistoryController.class);

    @Autowired
    private TransactionRepository dbRepo;

    @Value("${EXTRA_LATENCY_MILLIS:#{null}}")
    private Integer extraLatencyMillis;
    @Value("${HISTORY_LIMIT:100}")
    private Integer historyLimit;
    private String version;

    private JWTVerifier verifier;
    private LedgerReader ledgerReader;
    private LoadingCache<String, Deque<Transaction>> cache;

    /**
     * Constructor.
     *
     * Initializes JWT verifier and a connection to the bank ledger.
     */
    @Autowired
    public TransactionHistoryController(LedgerReader reader,
            StackdriverMeterRegistry meterRegistry,
            JWTVerifier verifier,
            @Value("${PUB_KEY_PATH}") final String publicKeyPath,
            LoadingCache<String, Deque<Transaction>> cache,
            @Value("${LOCAL_ROUTING_NUM}") final String localRoutingNum,
            @Value("${VERSION}") final String version) {
        this.version = version;
        // Initialize JWT verifier.
        this.verifier = verifier;
        // Initialize cache
        this.cache = cache;
        GuavaCacheMetrics.monitor(meterRegistry, this.cache, "Guava");
        // Initialize transaction processor.
        this.ledgerReader = reader;
        LOGGER.debug("Initialized transaction processor");
        this.ledgerReader.startWithCallback(transaction -> {
            final String fromId = transaction.getFromAccountNum();
            final String fromRouting = transaction.getFromRoutingNum();
            final String toId = transaction.getToAccountNum();
            final String toRouting = transaction.getToRoutingNum();

            if (fromRouting.equals(localRoutingNum)
                    && this.cache.asMap().containsKey(fromId)) {
                processTransaction(fromId, transaction);
            }
            if (toRouting.equals(localRoutingNum)
                    && this.cache.asMap().containsKey(toId)) {
                processTransaction(toId, transaction);
            }
        });
    }

    /**
     * Helper function to add a single transaction to the internal cache
     *
     * @param accountId   the accountId associated with the transaction
     * @param transaction the full transaction object
     */
    private void processTransaction(String accountId, Transaction transaction) {
        LOGGER.debug("Modifying transaction cache: " + accountId);
        Deque<Transaction> tList = this.cache.asMap()
                                             .get(accountId);
        tList.addFirst(transaction);
        // Drop old transactions
        if (tList.size() > historyLimit) {
            tList.removeLast();
        }
    }

   /**
     * Version endpoint.
     *
     * @return  service version string
     */
    @GetMapping("/version")
    public ResponseEntity version() {
        return new ResponseEntity<>(version, HttpStatus.OK);
    }

    /**
     * Readiness probe endpoint.
     *
     * @return HTTP Status 200 if server is ready to receive requests.
     */
    @GetMapping("/ready")
    @ResponseStatus(HttpStatus.OK)
    public String readiness() {
        return "ok";
    }

    /**
     * Liveness probe endpoint.
     *
     * @return HTTP Status 200 if server is healthy and serving requests.
     */
    @GetMapping("/healthy")
    public ResponseEntity liveness() {
        if (!ledgerReader.isAlive()) {
            // background thread died.
            LOGGER.error("Ledger reader not healthy");
            return new ResponseEntity<>("Ledger reader not healthy",
                                              HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("ok", HttpStatus.OK);
    }

    /**
     * Return a list of transactions for the specified account.
     *
     * The currently authenticated user must be allowed to access the account.
     * @param bearerToken  HTTP request 'Authorization' header
     * @param accountId    the account to get transactions for.
     * @return             a list of transactions for this account.
     */
    @GetMapping("/transactions/{accountId}")
    public ResponseEntity<?> getTransactions(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            // Check that the authenticated user can access this account.
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to retrieve account transactions: "
                    + "not authorized");
                return new ResponseEntity<>("not authorized",
                                              HttpStatus.UNAUTHORIZED);
            }

            // Load from cache
            Deque<Transaction> historyList = cache.get(accountId);

            // Set artificial extra latency.
            LOGGER.debug("Setting artificial latency");
            if (extraLatencyMillis != null) {
                try {
                    Thread.sleep(extraLatencyMillis);
                } catch (InterruptedException e) {
                    // Fake latency interrupted. Continue.
                }
            }

            return new ResponseEntity<Collection<Transaction>>(
                    historyList, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to retrieve account transactions: "
                + "not authorized");
            return new ResponseEntity<>("not authorized",
                                              HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error",
                                              HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Export transactions for the specified account as CSV, with optional
     * from/to date filters.
     *
     * Query params accept ISO-8601 timestamps (e.g. 2024-01-01T00:00:00Z),
     * dates (e.g. 2024-01-01 assume start of day UTC), or epoch millis.
     *
     * @param bearerToken  HTTP request 'Authorization' header
     * @param accountId    the account to export transactions for.
     * @param from         optional start time filter
     * @param to           optional end time filter
     * @return             CSV content of filtered transactions.
     */
    @GetMapping(value = "/transactions/{accountId}/export")
    public ResponseEntity<?> exportTransactionsCsv(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to export account transactions: not authorized");
                return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
            }

            Deque<Transaction> historyList = cache.get(accountId);

            Instant fromInstant = parseInstantOrNull(from, true);
            Instant toInstant = parseInstantOrNull(to, false);

            StringBuilder sb = new StringBuilder();
            sb.append("transactionId,fromAccountNum,fromRoutingNum,toAccountNum,toRoutingNum,amount,timestamp\n");

            Iterator<Transaction> it = historyList.iterator();
            while (it.hasNext()) {
                Transaction t = it.next();
                Instant ts = t.getTimestamp() != null ? t.getTimestamp().toInstant() : null;

                boolean include = true;
                if (fromInstant != null && ts != null) {
                    include = include && !ts.isBefore(fromInstant);
                }
                if (toInstant != null && ts != null) {
                    include = include && !ts.isAfter(toInstant);
                }
                // If timestamp is null, include only when no filters are set
                if (ts == null && (fromInstant != null || toInstant != null)) {
                    include = false;
                }

                if (include) {
                    sb.append(t.getTransactionId()).append(",");
                    sb.append(t.getFromAccountNum()).append(",");
                    sb.append(t.getFromRoutingNum()).append(",");
                    sb.append(t.getToAccountNum()).append(",");
                    sb.append(t.getToRoutingNum()).append(",");
                    sb.append(t.getAmount()).append(",");
                    sb.append(ts != null ? ts.toString() : "").append("\n");
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transactions.csv\"");

            return new ResponseEntity<>(sb.toString(), headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to export account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid date filter");
            return new ResponseEntity<>("invalid date filter", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Attempts to parse the provided string into an Instant. Supports:
     * - ISO-8601 timestamp e.g. 2024-01-01T12:00:00Z
     * - Date-only e.g. 2024-01-01 (assumes start/end of day UTC based on isStart)
     * - Epoch millis e.g. 1696118400000
     *
     * Returns null if input is null or empty.
     */
    private Instant parseInstantOrNull(String input, boolean isStart) throws IllegalArgumentException {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String s = input.trim();
        // Try epoch millis
        try {
            if (s.matches("^\\d{10,}$")) {
                long millis = Long.parseLong(s);
                return Instant.ofEpochMilli(millis);
            }
        } catch (NumberFormatException ignored) {
        }
        // Try ISO-8601 timestamp
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        // Try date-only
        try {
            LocalDate d = LocalDate.parse(s);
            if (isStart) {
                return d.atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                return d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
            }
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Unrecognized date format: " + input);
    }
}
}

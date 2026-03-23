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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Date;
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
    private String localRoutingNum;

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
        this.localRoutingNum = localRoutingNum;
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
     * Export transactions for the specified account as CSV with optional from/to filters.
     *
     * Date parameters support ISO-8601 timestamps (e.g., 2023-01-01T00:00:00Z) or
     * date-only (YYYY-MM-DD). When date-only is provided, from is interpreted
     * as 00:00:00Z and to as 23:59:59.999Z of that day.
     */
    @GetMapping(value = "/transactions/{accountId}/csv", produces = "text/csv")
    public ResponseEntity<?> exportTransactionsCsv(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(value = "from", required = false) String fromParam,
            @RequestParam(value = "to", required = false) String toParam) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }

        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to export account transactions: not authorized");
                return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
            }

            Date from = null;
            Date to = null;

            try {
                if (fromParam != null && !fromParam.isEmpty()) {
                    from = parseDate(fromParam, false);
                }
                if (toParam != null && !toParam.isEmpty()) {
                    to = parseDate(toParam, true);
                }
            } catch (DateTimeParseException e) {
                LOGGER.error("Invalid date parameter");
                return new ResponseEntity<>("invalid date parameter", HttpStatus.BAD_REQUEST);
            }

            List<Transaction> transactions;
            if (from != null && to != null) {
                transactions = dbRepo.findForAccountBetween(accountId, localRoutingNum, from, to);
            } else if (from != null) {
                transactions = dbRepo.findForAccountFrom(accountId, localRoutingNum, from);
            } else if (to != null) {
                transactions = dbRepo.findForAccountTo(accountId, localRoutingNum, to);
            } else {
                transactions = dbRepo.findForAccountAll(accountId, localRoutingNum);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("transactionId,timestamp,fromAccountNum,fromRoutingNum,toAccountNum,toRoutingNum,amountDollars\n");
            for (Transaction t : transactions) {
                String ts = t.getTimestamp() != null
                        ? Instant.ofEpochMilli(t.getTimestamp().getTime()).toString()
                        : "";
                double amountDollars = t.getAmount() / 100.0;
                sb.append(t.getTransactionId()).append(",")
                  .append(ts).append(",")
                  .append(t.getFromAccountNum()).append(",")
                  .append(t.getFromRoutingNum()).append(",")
                  .append(t.getToAccountNum()).append(",")
                  .append(t.getToRoutingNum()).append(",")
                  .append(String.format("%.2f", amountDollars))
                  .append("\n");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("text/csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transactions.csv\"");

            return new ResponseEntity<>(sb.toString(), headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to export account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        }
    }

    private Date parseDate(String param, boolean endOfDay) throws DateTimeParseException {
        // Try ISO-8601 instant first
        try {
            Instant instant = Instant.parse(param);
            return Date.from(instant);
        } catch (DateTimeParseException ignored) {
        }
        // Try date-only YYYY-MM-DD
        LocalDate ld = LocalDate.parse(param);
        OffsetDateTime odt = endOfDay
                ? ld.atTime(23, 59, 59, 999_000_000).atOffset(ZoneOffset.UTC)
                : ld.atStartOfDay().atOffset(ZoneOffset.UTC);
        return Date.from(odt.toInstant());
    }
}

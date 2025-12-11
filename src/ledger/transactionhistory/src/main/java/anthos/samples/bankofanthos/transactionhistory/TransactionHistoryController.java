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
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
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
     * Return the transaction history for the specified account as CSV.
     *
     * The currently authenticated user must be allowed to access the account.
     * Optional fromDate/toDate filters can be provided as ISO dates (yyyy-MM-dd).
     * @param bearerToken  HTTP request 'Authorization' header
     * @param accountId    the account to get transactions for.
     * @param fromDate     optional start date (inclusive), ISO format yyyy-MM-dd
     * @param toDate       optional end date (inclusive), ISO format yyyy-MM-dd
     * @return             CSV string of transactions for this account.
     */
    @GetMapping(value = "/transactions/{accountId}/csv",
                produces = "text/csv")
    public ResponseEntity<?> exportTransactionsCsv(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(name = "fromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(name = "toDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            // Check that the authenticated user can access this account.
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to retrieve account transactions CSV: "
                    + "not authorized");
                return new ResponseEntity<>("not authorized",
                                                  HttpStatus.UNAUTHORIZED);
            }

            Deque<Transaction> historyList = cache.get(accountId);

            LocalDate from = fromDate;
            LocalDate to = toDate;
            ZoneId zoneId = ZoneId.systemDefault();

            Collection<Transaction> filtered = historyList.stream()
                    .filter(t -> {
                        if (from == null && to == null) {
                            return true;
                        }
                        if (t.getTimestamp() == null) {
                            return false;
                        }
                        LocalDate txDate = t.getTimestamp()
                                .toInstant()
                                .atZone(zoneId)
                                .toLocalDate();
                        if (from != null && txDate.isBefore(from)) {
                            return false;
                        }
                        if (to != null && txDate.isAfter(to)) {
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            // Set artificial extra latency.
            LOGGER.debug("Setting artificial latency");
            if (extraLatencyMillis != null) {
                try {
                    Thread.sleep(extraLatencyMillis);
                } catch (InterruptedException e) {
                    // Fake latency interrupted. Continue.
                }
            }

            SimpleDateFormat formatter =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
            StringBuilder csvBuilder = new StringBuilder();
            csvBuilder.append("transactionId,fromAccountNum,fromRoutingNum,")
                    .append("toAccountNum,toRoutingNum,amount,timestamp\n");

            for (Transaction t : filtered) {
                String timestampString = "";
                if (t.getTimestamp() != null) {
                    timestampString = formatter.format(t.getTimestamp());
                }
                csvBuilder.append(t.getTransactionId()).append(',')
                        .append(t.getFromAccountNum()).append(',')
                        .append(t.getFromRoutingNum()).append(',')
                        .append(t.getToAccountNum()).append(',')
                        .append(t.getToRoutingNum()).append(',')
                        .append(t.getAmount()).append(',')
                        .append(timestampString)
                        .append('\n');
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"transactions.csv\"");

            return new ResponseEntity<>(csvBuilder.toString(),
                    headers,
                    HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to retrieve account transactions CSV: "
                + "not authorized");
            return new ResponseEntity<>("not authorized",
                                              HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error",
                                              HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

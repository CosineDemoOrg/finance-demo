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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
     * Export a user's transaction history as CSV with optional date filters.
     *
     * The currently authenticated user must be allowed to access the account.
     * @param bearerToken  HTTP request 'Authorization' header
     * @param accountId    the account to get transactions for.
     * @param fromDate     optional start date (inclusive) in ISO-8601 format (yyyy-MM-dd)
     * @param toDate       optional end date (inclusive) in ISO-8601 format (yyyy-MM-dd)
     * @return             CSV content for the account's transactions.
     */
    @GetMapping(value = "/transactions/{accountId}/export",
            produces = "text/csv")
    public ResponseEntity<String> exportTransactionsCsv(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            Date fromDate,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            Date toDate) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to export account transactions: "
                    + "not authorized");
                return new ResponseEntity<>("not authorized",
                                              HttpStatus.UNAUTHORIZED);
            }

            // Make the end date inclusive by moving it to the end of the day.
            if (toDate != null) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(toDate);
                calendar.add(Calendar.DATE, 1);
                toDate = calendar.getTime();
            }

            List<Transaction> transactions =
                dbRepo.findForAccountInRange(accountId,
                                             localRoutingNum,
                                             fromDate,
                                             toDate);

            String csv = toCsv(transactions);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"transactions-" + accountId + ".csv\"");

            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to export account transactions: "
                + "not authorized");
            return new ResponseEntity<>("not authorized",
                                              HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Serialize transactions to CSV.
     */
    private String toCsv(List<Transaction> transactions) {
        StringBuilder builder = new StringBuilder();
        builder.append("transactionId,timestamp,fromAccountNum,fromRoutingNum,")
               .append("toAccountNum,toRoutingNum,amount\n");

        DateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

        for (Transaction t : transactions) {
            builder.append(t.getTransactionId()).append(",");
            builder.append(dateFormat.format(t.getTimestamp())).append(",");
            builder.append(escapeCsv(t.getFromAccountNum())).append(",");
            builder.append(escapeCsv(t.getFromRoutingNum())).append(",");
            builder.append(escapeCsv(t.getToAccountNum())).append(",");
            builder.append(escapeCsv(t.getToRoutingNum())).append(",");
            builder.append(t.getAmount());
            builder.append("\n");
        }

        return builder.toString();
    }

    /**
     * Escape a value for inclusion in a CSV file.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean hasSpecial =
            value.contains(",") || value.contains("\"") || value.contains("\n")
            || value.contains("\r");
        if (!hasSpecial) {
            return value;
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}

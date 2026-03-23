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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
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

    private final TransactionRepository dbRepo;

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
            TransactionRepository dbRepo,
            @Value("${VERSION}") final String version) {
        this.version = version;
        this.localRoutingNum = localRoutingNum;
        this.dbRepo = dbRepo;
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

            if (fromRouting.equals(this.localRoutingNum)
                    && this.cache.asMap().containsKey(fromId)) {
                processTransaction(fromId, transaction);
            }
            if (toRouting.equals(this.localRoutingNum)
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

    @GetMapping("/transactions/export")
    public ResponseEntity<?> exportTransactions(
            @RequestHeader("Authorization") String bearerToken,
            @RequestParam(name = "account_id") String accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }

        LocalDate fromDate;
        LocalDate toDate;
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (from == null && to == null) {
                toDate = today;
                fromDate = today.minusDays(90);
            } else if (from != null && to == null) {
                fromDate = LocalDate.parse(from);
                toDate = today;
            } else if (from == null) {
                toDate = LocalDate.parse(to);
                fromDate = toDate.minusDays(90);
            } else {
                fromDate = LocalDate.parse(from);
                toDate = LocalDate.parse(to);
            }
        } catch (DateTimeParseException e) {
            return new ResponseEntity<>("invalid date format",
                HttpStatus.BAD_REQUEST);
        }

        if (fromDate.isAfter(toDate)) {
            return new ResponseEntity<>("from must be <= to",
                HttpStatus.BAD_REQUEST);
        }

        if (fromDate.plusYears(1).isBefore(toDate)) {
            return new ResponseEntity<>("date range must be <= 1 year",
                HttpStatus.BAD_REQUEST);
        }

        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                return new ResponseEntity<>("not authorized",
                    HttpStatus.UNAUTHORIZED);
            }

            Deque<Transaction> historyList = cache.get(accountId);
            List<Transaction> filteredTransactions = historyList.stream()
                .filter(t -> {
                    LocalDate txDate = Instant
                        .ofEpochMilli(t.getTimestamp().getTime())
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate();
                    return !(txDate.isBefore(fromDate)
                        || txDate.isAfter(toDate));
                })
                .collect(Collectors.toList());

            String csv = buildCsv(accountId, filteredTransactions);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                String.format("attachment; filename=transactions_%s_%s_%s.csv",
                    accountId, fromDate.toString(), toDate.toString()));

            return new ResponseEntity<>(csv.getBytes(StandardCharsets.UTF_8),
                headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            return new ResponseEntity<>("not authorized",
                HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            return new ResponseEntity<>("cache error",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String buildCsv(String accountId, List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder();
        sb.append("date,description,amount,currency,balance_after,transaction_id\n");
        for (Transaction t : transactions) {
            LocalDate txDate = Instant
                .ofEpochMilli(t.getTimestamp().getTime())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
            String description = buildDescription(accountId, t);
            String amount = centsToDecimalString(buildSignedAmount(accountId, t));
            String currency = "USD";
            Integer balanceCents = dbRepo.balanceAt(accountId, localRoutingNum,
                t.getTimestamp());
            String balanceAfter = centsToDecimalString(balanceCents);

            sb.append(txDate.toString()).append(',')
                .append(csvEscape(description)).append(',')
                .append(amount).append(',')
                .append(currency).append(',')
                .append(balanceAfter).append(',')
                .append(t.getTransactionId())
                .append('\n');
        }
        return sb.toString();
    }

    private String buildDescription(String accountId, Transaction t) {
        if (accountId.equals(t.getToAccountNum())) {
            return String.format("Credit from %s", t.getFromAccountNum());
        }
        if (accountId.equals(t.getFromAccountNum())) {
            return String.format("Debit to %s", t.getToAccountNum());
        }
        return "Transaction";
    }

    private Integer buildSignedAmount(String accountId, Transaction t) {
        if (accountId.equals(t.getToAccountNum())) {
            return t.getAmount();
        }
        if (accountId.equals(t.getFromAccountNum())) {
            return -t.getAmount();
        }
        return t.getAmount();
    }

    private String centsToDecimalString(Integer cents) {
        BigDecimal dec = new BigDecimal(cents).divide(new BigDecimal(100), 2,
            RoundingMode.HALF_UP);
        return dec.toPlainString();
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (!value.contains(",") && !value.contains("\"")
            && !value.contains("\n") && !value.contains("\r")) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

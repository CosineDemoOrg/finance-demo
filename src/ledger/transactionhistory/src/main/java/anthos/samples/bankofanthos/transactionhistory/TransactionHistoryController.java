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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
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
     * Export transactions as CSV for the specified account with optional date filters.
     *
     * Query params:
     *  - from: inclusive start date (YYYY-MM-DD)
     *  - to: inclusive end date (YYYY-MM-DD)
     */
    @GetMapping(value = "/transactions/{accountId}/export", produces = "text/csv")
    public ResponseEntity<?> exportTransactionsCsv(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to export account transactions: not authorized");
                return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
            }

            Date fromDate = null;
            Date toDate = null;
            ZoneId zone = ZoneId.systemDefault();
            if (from != null && !from.isEmpty()) {
                try {
                    LocalDate d = LocalDate.parse(from);
                    fromDate = Date.from(d.atStartOfDay(zone).toInstant());
                } catch (DateTimeParseException ex) {
                    return new ResponseEntity<>("invalid 'from' date format, expected YYYY-MM-DD",
                            HttpStatus.BAD_REQUEST);
                }
            }
            if (to != null && !to.isEmpty()) {
                try {
                    LocalDate d = LocalDate.parse(to);
                    // end of day inclusive
                    toDate = Date.from(d.plusDays(1).atStartOfDay(zone).toInstant());
                } catch (DateTimeParseException ex) {
                    return new ResponseEntity<>("invalid 'to' date format, expected YYYY-MM-DD",
                            HttpStatus.BAD_REQUEST);
                }
            }

            Deque<Transaction> historyList = cache.get(accountId);

            StringBuilder csv = new StringBuilder();
            csv.append("transaction_id,timestamp,from_account,from_routing,to_account,to_routing,amount_cents\n");
            for (Transaction t : historyList) {
                Date ts = t.getTimestamp();
                if (fromDate != null && (ts == null || ts.before(fromDate))) {
                    continue;
                }
                if (toDate != null && (ts == null || !ts.before(toDate))) {
                    continue;
                }
                csv.append(t.getTransactionId()).append(",");
                csv.append(ts != null ? ts.toInstant().toString() : "").append(",");
                csv.append(t.getFromAccountNum()).append(",");
                csv.append(t.getFromRoutingNum()).append(",");
                csv.append(t.getToAccountNum()).append(",");
                csv.append(t.getToRoutingNum()).append(",");
                csv.append(t.getAmount()).append("\n");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("text/csv"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("transactions_" + accountId + ".csv").build());

            return new ResponseEntity<>(csv.toString(), headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to export account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    /**
     * Visualise transactions as a simple dashboard (HTML or JSON).
     *
     * Query params:
     *  - from: inclusive start date (YYYY-MM-DD)
     *  - to: inclusive end date (YYYY-MM-DD)
     *  - format: "html" (default) or "json"
     */
    @GetMapping("/transactions/{accountId}/visualise")
    public ResponseEntity<?> visualiseTransactions(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "format", required = false) String format) {

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.split("Bearer ")[1];
        }
        try {
            DecodedJWT jwt = verifier.verify(bearerToken);
            if (!accountId.equals(jwt.getClaim("acct").asString())) {
                LOGGER.error("Failed to visualise account transactions: not authorized");
                return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
            }

            Date fromDate = null;
            Date toDate = null;
            ZoneId zone = ZoneId.systemDefault();
            if (from != null && !from.isEmpty()) {
                try {
                    LocalDate d = LocalDate.parse(from);
                    fromDate = Date.from(d.atStartOfDay(zone).toInstant());
                } catch (DateTimeParseException ex) {
                    return new ResponseEntity<>("invalid 'from' date format, expected YYYY-MM-DD",
                            HttpStatus.BAD_REQUEST);
                }
            }
            if (to != null && !to.isEmpty()) {
                try {
                    LocalDate d = LocalDate.parse(to);
                    // end of day inclusive
                    toDate = Date.from(d.plusDays(1).atStartOfDay(zone).toInstant());
                } catch (DateTimeParseException ex) {
                    return new ResponseEntity<>("invalid 'to' date format, expected YYYY-MM-DD",
                            HttpStatus.BAD_REQUEST);
                }
            }

            Deque<Transaction> historyList = cache.get(accountId);

            java.util.Map<LocalDate, long[]> stats = new java.util.TreeMap<>();
            for (Transaction t : historyList) {
                Date ts = t.getTimestamp();
                if (ts == null) {
                    continue;
                }
                if (fromDate != null && ts.before(fromDate)) {
                    continue;
                }
                if (toDate != null && !ts.before(toDate)) {
                    continue;
                }
                LocalDate day = ts.toInstant().atZone(zone).toLocalDate();
                long[] arr = stats.computeIfAbsent(day, k -> new long[] {0L, 0L});
                arr[0] += 1; // count
                arr[1] += (t.getAmount() == null ? 0 : t.getAmount()); // sum amount in cents
            }

            // Prepare series
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<Long> counts = new java.util.ArrayList<>();
            java.util.List<Long> amounts = new java.util.ArrayList<>();
            for (java.util.Map.Entry<LocalDate, long[]> e : stats.entrySet()) {
                labels.add(e.getKey().toString());
                counts.add(e.getValue()[0]);
                amounts.add(e.getValue()[1]);
            }

            boolean json = format != null && format.equalsIgnoreCase("json");
            if (json) {
                java.util.Map<String, Object> dashboard = new java.util.LinkedHashMap<>();
                dashboard.put("accountId", accountId);
                dashboard.put("from", from);
                dashboard.put("to", to);
                java.util.List<java.util.Map<String, Object>> series = new java.util.ArrayList<>();
                for (int i = 0; i < labels.size(); i++) {
                    java.util.Map<String, Object> point = new java.util.LinkedHashMap<>();
                    point.put("date", labels.get(i));
                    point.put("count", counts.get(i));
                    point.put("amount_cents", amounts.get(i));
                    series.add(point);
                }
                dashboard.put("series", series);
                return new ResponseEntity<>(dashboard, HttpStatus.OK);
            } else {
                StringBuilder html = new StringBuilder();
                html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
                html.append("<title>Transactions Dashboard</title>");
                html.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\"></script>");
                html.append("<style>body{font-family:sans-serif;margin:24px;} .charts{display:flex;gap:32px;flex-wrap:wrap;} .chart{width:600px;max-width:100%;}</style>");
                html.append("</head><body>");
                html.append("<h2>Transactions Dashboard</h2>");
                html.append("<p>Account: ").append(accountId).append("</p>");
                if ((from != null && !from.isEmpty()) || (to != null && !to.isEmpty())) {
                    html.append("<p>Range: ")
                        .append(from != null ? from : "")
                        .append(" to ")
                        .append(to != null ? to : "")
                        .append("</p>");
                }
                html.append("<div class=\"charts\">");
                html.append("<div class=\"chart\"><canvas id=\"countChart\"></canvas></div>");
                html.append("<div class=\"chart\"><canvas id=\"amountChart\"></canvas></div>");
                html.append("</div>");
                // Embed data
                html.append("<script>");
                html.append("const labels=").append(toJsonArray(labels)).append(";");
                html.append("const counts=").append(toJsonArrayLong(counts)).append(";");
                html.append("const amounts=").append(toJsonArrayLong(amounts)).append(";");
                html.append("const ctx1=document.getElementById('countChart');");
                html.append("new Chart(ctx1,{type:'bar',data:{labels:labels,datasets:[{label:'Transactions per day',data:counts,backgroundColor:'rgba(54,162,235,0.5)'}]},options:{responsive:true,plugins:{legend:{display:true}},scales:{x:{ticks:{autoSkip:false}}}}});");
                html.append("const ctx2=document.getElementById('amountChart');");
                html.append("new Chart(ctx2,{type:'line',data:{labels:labels,datasets:[{label:'Amount (USD) per day',data:amounts.map(a=>a/100.0),borderColor:'rgba(255,99,132,0.8)',tension:0.2}]},options:{responsive:true,plugins:{legend:{display:true}},scales:{x:{ticks:{autoSkip:false}},y:{beginAtZero:true}}}});");
                html.append("</script>");
                html.append("</body></html>");

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.TEXT_HTML);
                return new ResponseEntity<>(html.toString(), headers, HttpStatus.OK);
            }

        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to visualise account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Helpers to serialize arrays for inline JS without introducing new dependencies
    private String toJsonArray(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder(\"[\");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) { sb.append(','); }
            String s = items.get(i);
            // very simple escaping for quotes and backslashes
            s = s.replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\");
            sb.append('\"').append(s).append('\"');
        }
        sb.append(']');
        return sb.toString();
    }

    private String toJsonArrayLong(java.util.List<Long> items) {
        StringBuilder sb = new StringBuilder(\"[\");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) { sb.append(','); }
            sb.append(items.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
}

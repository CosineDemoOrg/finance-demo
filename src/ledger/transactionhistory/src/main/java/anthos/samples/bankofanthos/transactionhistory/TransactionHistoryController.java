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

            String csv = buildCsv(historyList, fromDate, toDate);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("text/csv"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("transactions_" + accountId + ".csv").build());

            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to export account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Visualise transactions over time for the specified account.
     *
     * Returns a simple HTML page with a chart by default, or JSON if format=json.
     * Supports the same 'from' and 'to' date filters as the CSV export.
     */
    @GetMapping("/transactions/{accountId}/visualise")
    public ResponseEntity<?> visualiseTransactions(
            @RequestHeader("Authorization") String bearerToken,
            @PathVariable String accountId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "format", required = false, defaultValue = "html") String format) {

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
            String csv = buildCsv(historyList, fromDate, toDate);

            // Parse CSV and aggregate counts per day
            java.util.LinkedHashMap<java.time.LocalDate, long[]> daily = new java.util.LinkedHashMap<>();
            String[] lines = csv.split("\n");
            for (int i = 1; i < lines.length; i++) { // skip header
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length < 7) {
                    continue;
                }
                String ts = fields[1];
                String amtStr = fields[6];
                if (ts == null || ts.isEmpty()) {
                    continue;
                }
                java.time.Instant instant = java.time.Instant.parse(ts);
                java.time.LocalDate date = instant.atZone(zone).toLocalDate();
                long amt = 0;
                try {
                    amt = Long.parseLong(amtStr);
                } catch (NumberFormatException ignored) { }
                long[] agg = daily.getOrDefault(date, new long[]{0L, 0L});
                agg[0] += 1;        // count
                agg[1] += amt;      // amount_cents
                daily.put(date, agg);
            }

            if ("json".equalsIgnoreCase(format)) {
                java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("accountId", accountId);
                resp.put("from", from);
                resp.put("to", to);
                java.util.List<java.util.Map<String, Object>> points = new java.util.ArrayList<>();
                long totalCount = 0;
                long totalAmount = 0;
                java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
                for (java.util.Map.Entry<java.time.LocalDate, long[]> e : daily.entrySet()) {
                    java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
                    p.put("date", e.getKey().format(df));
                    p.put("count", e.getValue()[0]);
                    p.put("amount_cents", e.getValue()[1]);
                    totalCount += e.getValue()[0];
                    totalAmount += e.getValue()[1];
                    points.add(p);
                }
                resp.put("points", points);
                java.util.Map<String, Object> totals = new java.util.LinkedHashMap<>();
                totals.put("count", totalCount);
                totals.put("amount_cents", totalAmount);
                resp.put("totals", totals);
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resp);
            } else {
                String html = buildHtmlDashboard(accountId, daily);
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
            }
        } catch (JWTVerificationException e) {
            LOGGER.error("Failed to visualise account transactions: not authorized");
            return new ResponseEntity<>("not authorized", HttpStatus.UNAUTHORIZED);
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.error("Cache error");
            return new ResponseEntity<>("cache error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Build CSV string from transactions with optional date filtering.
     */
    private String buildCsv(Deque<Transaction> historyList, Date fromDate, Date toDate) {
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
        return csv.toString();
    }

    /**
     * Build a simple HTML dashboard using Google Charts to show volume over time.
     */
    private String buildHtmlDashboard(String accountId, java.util.LinkedHashMap<java.time.LocalDate, long[]> daily) {
        StringBuilder sb = new StringBuilder();
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        sb.append("<title>Transactions for ").append(accountId).append("</title>");
        sb.append("<script type=\"text/javascript\" src=\"https://www.gstatic.com/charts/loader.js\"></script>");
        sb.append("<script type=\"text/javascript\">");
        sb.append("google.charts.load('current', {'packages':['corechart']});");
        sb.append("google.charts.setOnLoadCallback(drawChart);");
        sb.append("function drawChart(){");
        sb.append("var data = new google.visualization.DataTable();");
        sb.append("data.addColumn('string','Date');");
        sb.append("data.addColumn('number','Transactions');");
        sb.append("data.addColumn('number','Amount ($)');");
        sb.append("data.addRows([");
        boolean first = true;
        for (java.util.Map.Entry<java.time.LocalDate, long[]> e : daily.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            double dollars = e.getValue()[1] / 100.0;
            sb.append("[")
              .append("'").append(e.getKey().format(df)).append("',")
              .append(e.getValue()[0]).append(",")
              .append(String.format(java.util.Locale.US, "%.2f", dollars))
              .append("]");
        }
        sb.append("]);");
        sb.append("var options = {title:'Transaction volume over time',legend:{position:'bottom'},height:400};");
        sb.append("var chart = new google.visualization.LineChart(document.getElementById('chart'));");
        sb.append("chart.draw(data, options);");
        sb.append("}");
        sb.append("</script>");
        sb.append("</head><body>");
        sb.append("<h2>Transactions for ").append(accountId).append("</h2>");
        sb.append("<div id=\"chart\"></div>");
        sb.append("</body></html>");
        return sb.toString();
    }
}

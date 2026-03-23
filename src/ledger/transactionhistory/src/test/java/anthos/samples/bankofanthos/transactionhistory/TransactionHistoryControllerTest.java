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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.lang.Nullable;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransactionHistoryControllerTest {

    private TransactionHistoryController transactionHistoryController;

    @Mock
    private JWTVerifier verifier;
    @Mock
    private LedgerReader ledgerReader;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private Clock clock;
    @Mock
    private LoadingCache<String, Deque<Transaction>> cache;
    @Mock
    private CacheStats stats;
    @Mock
    private Deque<Transaction> transactions;
    @Mock
    private TransactionRepository dbRepo;

    private static final String VERSION = "v0.2.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String OK_CODE = "ok";
    private static final String JWT_ACCOUNT_KEY = "acct";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String NON_AUTHED_ACCOUNT_NUM = "9876543210";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String PUBLIC_KEY_PATH = "path/";

    @BeforeEach
    void setUp() {
        initMocks(this);
        StackdriverMeterRegistry meterRegistry = new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String projectId() {
                return "test";
            }

            @Override
            @Nullable
            public String get(String key) {
                return null;
            }
        }, clock);

        when(cache.stats()).thenReturn(stats);
        transactionHistoryController = new TransactionHistoryController(ledgerReader,
            meterRegistry, verifier, PUBLIC_KEY_PATH, cache, LOCAL_ROUTING_NUM, dbRepo, VERSION);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given version number in the environment, " +
            "return a ResponseEntity with the version number")
    void version() {
        // When
        final ResponseEntity actualResult = transactionHistoryController.version();

        // Then
        assertNotNull(actualResult);
        assertEquals(VERSION, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the server is serving requests, return HTTP Status 200")
    void readiness() {
        // When
        final String actualResult = transactionHistoryController.readiness();

        // Then
        assertNotNull(actualResult);
        assertEquals(OK_CODE, actualResult);
    }

    @Test
    @DisplayName("Given the ledgerReader is alive, return HTTP Status 200")
    void livenessSucceedsWhenLedgerReaderIsAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(true);

        // When
        final ResponseEntity actualResult = transactionHistoryController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals(OK_CODE, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the ledgerReader is not alive, return HTTP Status 500")
    void livenessFailsWhenLedgerReaderIsNotAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(false);
        
        // When
        final ResponseEntity actualResult = transactionHistoryController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is authenticated for the account, return HTTP Status 200")
    void getTransactionsSucceedsWhenAccountMatchesAuthenticatedUser() throws Exception {
        // Given
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(cache.get(AUTHED_ACCOUNT_NUM)).thenReturn(transactions);

        // When
        final ResponseEntity actualResult = transactionHistoryController
            .getTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is authenticated but cannot access the account, return 401")
    void getTransactionsFailsWhenAccountDoesNotMatchAuthenticatedUser() {
        // Given
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult = transactionHistoryController.getTransactions(BEARER_TOKEN, NON_AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is not authenticated, return 401")
    void getTransactionsFailsWhenUserNotAuthenticated() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(JWTVerificationException.class);

        // When
        final ResponseEntity actualResult = transactionHistoryController.getTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the cache throws an error for an authenticated user, return 500")
    void getTransactionsFailsWhenCacheThrowsError() throws Exception {
        // Given
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(cache.get(AUTHED_ACCOUNT_NUM)).thenThrow(ExecutionException.class);

        // When
        final ResponseEntity actualResult = transactionHistoryController
            .getTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given valid date range, return CSV with filtered transactions")
    void exportTransactionsReturnsCsvWithFilters() throws Exception {
        // Given
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);

        Transaction inRange = mock(Transaction.class);
        Date inRangeDate = Date.from(LocalDate.of(2024, 1, 15)
            .atStartOfDay().toInstant(ZoneOffset.UTC));
        when(inRange.getTimestamp()).thenReturn(inRangeDate);
        when(inRange.getToAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(inRange.getFromAccountNum()).thenReturn("111");
        when(inRange.getAmount()).thenReturn(250);
        when(inRange.getTransactionId()).thenReturn(10L);

        Transaction outOfRange = mock(Transaction.class);
        Date outOfRangeDate = Date.from(LocalDate.of(2023, 1, 15)
            .atStartOfDay().toInstant(ZoneOffset.UTC));
        when(outOfRange.getTimestamp()).thenReturn(outOfRangeDate);

        Deque<Transaction> deque = new ArrayDeque<>();
        deque.add(inRange);
        deque.add(outOfRange);
        when(cache.get(AUTHED_ACCOUNT_NUM)).thenReturn(deque);

        when(dbRepo.balanceAt(AUTHED_ACCOUNT_NUM, LOCAL_ROUTING_NUM, inRangeDate))
            .thenReturn(1000);

        // When
        final ResponseEntity actualResult = transactionHistoryController
            .exportTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM,
                "2024-01-01", "2024-01-31");

        // Then
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
        assertEquals("text/csv", actualResult.getHeaders().getContentType().toString());
        String body = new String((byte[]) actualResult.getBody());
        assertEquals(true,
            body.startsWith("date,description,amount,currency,balance_after,transaction_id\n"));
        assertEquals(true,
            body.contains("2024-01-15,Credit from 111,2.50,USD,10.00,10\n"));
        assertEquals(false, body.contains("2023-01-15"));
    }

    @Test
    @DisplayName("Given invalid date input, return 400")
    void exportTransactionsInvalidDateReturns400() {
        final ResponseEntity actualResult = transactionHistoryController
            .exportTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM,
                "not-a-date", "2024-01-31");
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given from > to, return 400")
    void exportTransactionsFromAfterToReturns400() {
        final ResponseEntity actualResult = transactionHistoryController
            .exportTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM,
                "2024-02-01", "2024-01-01");
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given date range over 1 year, return 400")
    void exportTransactionsOverOneYearReturns400() {
        final ResponseEntity actualResult = transactionHistoryController
            .exportTransactions(BEARER_TOKEN, AUTHED_ACCOUNT_NUM,
                "2023-01-01", "2024-02-01");
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

}

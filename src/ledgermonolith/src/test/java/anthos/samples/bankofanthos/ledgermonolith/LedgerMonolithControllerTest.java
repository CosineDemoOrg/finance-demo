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

package anthos.samples.bankofanthos.ledgermonolith;

import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

class LedgerMonolithControllerTest {

    private LedgerMonolithController ledgerMonolithController;

    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private JWTVerifier verifier;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private LedgerReader ledgerReader;
    @Mock
    private LoadingCache<String, AccountInfo> ledgerReaderCache;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String NON_AUTHED_ACCOUNT_NUM = "9876543210";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String EXCEPTION_MESSAGE = "Invalid variable";
    private static final Long BALANCE = 4000L;
    private static final int SENDER_BALANCE = 40;
    private static final int LARGER_THAN_SENDER_BALANCE = 1000;
    private static final int SMALLER_THAN_SENDER_BALANCE = 10;

    @BeforeEach
    void setUp() {
        initMocks(this);
        // LedgerReader.startWithCallback is called in the constructor,
        // so we must stub it before constructing the controller.
        doNothing().when(ledgerReader).startWithCallback(any());

        ledgerMonolithController = new LedgerMonolithController(
                "unused-pub-key-path",
                ledgerReaderCache,
                verifier,
                transactionRepository,
                transactionValidator,
                ledgerReader,
                LOCAL_ROUTING_NUM,
                VERSION);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerMonolithController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given version number in the environment, " +
            "return a ResponseEntity with the version number")
    void version() {
        // When
        final ResponseEntity actualResult = ledgerMonolithController.version();

        // Then
        assertNotNull(actualResult);
        assertEquals(VERSION, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the server is serving requests, return HTTP Status 200")
    void readiness() {
        // When
        final ResponseEntity<String> actualResult = ledgerMonolithController.readiness();

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the ledgerReader is alive, return HTTP Status 200")
    void livenessSucceedsWhenLedgerReaderIsAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(true);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals("ok", actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the ledgerReader is not alive, return HTTP Status 500")
    void livenessFailsWhenLedgerReaderIsNotAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(false);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResult.getStatusCode());
    }

    // ========== addTransaction tests ==========

    @Test
    @DisplayName("Given the transaction is external, return HTTP Status 201")
    void addTransactionSuccessWhenExternal(TestInfo testInfo) {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount < sender balance, " +
            "return HTTP Status 201")
    void addTransactionSuccessWhenInternalWithSufficientBalance(TestInfo testInfo) {
        // Given
        LedgerMonolithController spyController =
                spy(ledgerMonolithController);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn((long) SENDER_BALANCE).when(
                spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount > sender balance, " +
            "return HTTP Status 400")
    void addTransactionFailWhenInternalWithInsufficientBalance(TestInfo testInfo) {
        // Given
        LedgerMonolithController spyController =
                spy(ledgerMonolithController);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(LARGER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn((long) SENDER_BALANCE).when(
                spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given HTTP request 'Authorization' header is null, " +
            "return HTTP Status 400")
    void addTransactionWhenBearerTokenNull() {
        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(
                        null, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given JWT verifier cannot verify the given bearer token, " +
            "return HTTP Status 401")
    void addTransactionWhenJWTVerificationExceptionThrown() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(
                JWTVerificationException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.UNAUTHORIZED_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given exception thrown on validation, return HTTP Status 400")
    void addTransactionWhenValidationFails() {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getRequestUuid()).thenReturn("some-uuid");
        doThrow(new IllegalArgumentException(EXCEPTION_MESSAGE)).
                when(transactionValidator).validateTransaction(
                        LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(
                BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("When duplicate UUID transactions are sent, " +
            "second one is rejected with HTTP status 400")
    void addTransactionWhenDuplicateUuid(TestInfo testInfo) {
        // Given
        LedgerMonolithController spyController =
                spy(ledgerMonolithController);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn((long) SENDER_BALANCE).when(
                spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity originalResult =
                spyController.addTransaction(
                        BEARER_TOKEN, transaction);
        final ResponseEntity duplicateResult =
                spyController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(originalResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                originalResult.getBody());
        assertEquals(HttpStatus.CREATED, originalResult.getStatusCode());

        assertNotNull(duplicateResult);
        assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION,
                duplicateResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, duplicateResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction cannot be saved to the repository, " +
            "return HTTP Status 500")
    void addTransactionWhenCannotCreateTransactionExceptionThrown(TestInfo testInfo) {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new CannotCreateTransactionException(EXCEPTION_MESSAGE)).when(
                transactionRepository).save(transaction);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE, actualResult.getBody());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    // ========== getBalance tests ==========

    @Test
    @DisplayName("Given the user is authenticated for the account, return HTTP Status 200")
    void getBalanceSucceedsWhenAccountMatchesAuthenticatedUser() throws Exception {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        Deque<Transaction> txns = new ArrayDeque<>();
        AccountInfo info = new AccountInfo(BALANCE, txns);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(info);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getBalance(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is authenticated for the account, return correct balance")
    void getBalanceReturnsCorrectValue() throws Exception {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        Deque<Transaction> txns = new ArrayDeque<>();
        AccountInfo info = new AccountInfo(BALANCE, txns);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(info);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getBalance(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(BALANCE, actualResult.getBody());
    }

    @Test
    @DisplayName("Given the user is authenticated but cannot access the account, return 401")
    void getBalanceFailsWhenAccountDoesNotMatchAuthenticatedUser() {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getBalance(
                BEARER_TOKEN, NON_AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is not authenticated, return 401")
    void getBalanceFailsWhenUserNotAuthenticated() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(JWTVerificationException.class);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getBalance(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the cache throws an error for an authenticated user, return 500")
    void getBalanceFailsWhenCacheThrowsError() throws Exception {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenThrow(ExecutionException.class);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getBalance(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResult.getStatusCode());
    }

    // ========== getTransactions tests ==========

    @Test
    @DisplayName("Given the user is authenticated for the account, return HTTP Status 200 with transactions")
    void getTransactionsSucceedsWhenAccountMatchesAuthenticatedUser() throws Exception {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        Deque<Transaction> txns = new ArrayDeque<>();
        txns.add(transaction);
        AccountInfo info = new AccountInfo(BALANCE, txns);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(info);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getTransactions(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user is authenticated but cannot access the account, return 401")
    void getTransactionsFailsWhenAccountDoesNotMatchAuthenticatedUser() {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getTransactions(
                BEARER_TOKEN, NON_AUTHED_ACCOUNT_NUM);

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
        final ResponseEntity actualResult = ledgerMonolithController.getTransactions(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the cache throws an error for an authenticated user, return 500")
    void getTransactionsFailsWhenCacheThrowsError() throws Exception {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenThrow(ExecutionException.class);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.getTransactions(
                BEARER_TOKEN, AUTHED_ACCOUNT_NUM);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResult.getStatusCode());
    }
}

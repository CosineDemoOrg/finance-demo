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

import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransactionFeeTest {

    private LedgerMonolithController ledgerMonolithController;

    @Mock private TransactionValidator transactionValidator;
    @Mock private TransactionRepository transactionRepository;
    @Mock private JWTVerifier verifier;
    @Mock private Transaction transaction;
    @Mock private DecodedJWT jwt;
    @Mock private Claim claim;
    @Mock private LedgerReader ledgerReader;
    @Mock private LoadingCache<String, AccountInfo> ledgerReaderCache;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";

    @BeforeEach
    void setUp() {
        initMocks(this);
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
        when(jwt.getClaim(LedgerMonolithController.JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
    }

    @Test
    @DisplayName("Given amount is 1 cent, fee rounds to 0, total amount is 1")
    void feeIsAppliedOnSmallAmount(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(1);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(100L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(1);
    }

    @Test
    @DisplayName("Given amount is $100 (10000 cents), " +
            "fee is 250 cents (2.5%), total amount is 10250")
    void feeOnOneHundredDollars(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(10250L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(10250);
    }

    @Test
    @DisplayName("Given amount is $1000 (100000 cents), " +
            "fee is 2500 cents (2.5%), total amount is 102500")
    void feeOnOneThousandDollars(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(100000);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(102500L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(102500);
    }

    @Test
    @DisplayName("Given balance covers amount but not amount+fee, " +
            "return HTTP Status 400 insufficient balance")
    void insufficientBalanceWhenFeeExceedsMargin(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        // Balance of 10050 covers 10000 but not 10000+250=10250 (2.5% fee)
        doReturn(10050L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE, actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given balance exactly covers amount+fee, return HTTP Status 201")
    void sufficientBalanceWhenFeeIncludedExactly(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        // Balance of 10250 exactly covers 10000+250=10250 (2.5% fee)
        doReturn(10250L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE, actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given amount is 150 cents, " +
            "fee rounds to 4 (2.5% of 150 = 3.75), total amount is 154")
    void feeRoundsCorrectly(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(150);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(200L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(154);
    }

    @Test
    @DisplayName("Given amount is $100,000 (10000000 cents), " +
            "fee is 250000 cents (2.5%), total amount is 10250000")
    void feeOnLargeAmount(TestInfo testInfo) {
        LedgerMonolithController spyController = spy(ledgerMonolithController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000000);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(10260000L).when(spyController).getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(10250000);
    }
}

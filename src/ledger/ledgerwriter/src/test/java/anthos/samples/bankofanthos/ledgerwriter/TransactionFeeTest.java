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

package anthos.samples.bankofanthos.ledgerwriter;

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.lang.Nullable;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransactionFeeTest {

    private LedgerWriterController ledgerWriterController;

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
    private Clock clock;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String BALANCES_API_ADDR = "balancereader:8080";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";

    @BeforeEach
    void setUp() {
        initMocks(this);
        StackdriverMeterRegistry meterRegistry = new StackdriverMeterRegistry(
                new StackdriverConfig() {
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

        ledgerWriterController = new LedgerWriterController(verifier,
                meterRegistry,
                transactionRepository, transactionValidator,
                LOCAL_ROUTING_NUM, BALANCES_API_ADDR, VERSION);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerWriterController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given amount is 1 cent, fee rounds to 0, " +
            "total amount is 1")
    void feeIsAppliedOnSmallAmount(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(1);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        doReturn(100).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(1);
    }

    @Test
    @DisplayName("Given amount is $100 (10000 cents), " +
            "fee is 65 cents (0.65%), total amount is 10065")
    void feeOnOneHundredDollars(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        doReturn(10065).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(10065);
    }

    @Test
    @DisplayName("Given amount is $1000 (100000 cents), " +
            "fee is 650 cents (0.65%), total amount is 100650")
    void feeOnOneThousandDollars(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(100000);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        doReturn(100650).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(100650);
    }

    @Test
    @DisplayName("Given balance covers amount but not amount+fee, " +
            "return HTTP Status 400 insufficient balance")
    void insufficientBalanceWhenFeeExceedsMargin(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        // Balance of 10050 covers 10000 but not 10000+65=10065 (0.65% fee)
        doReturn(10050).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given balance exactly covers amount+fee, " +
            "return HTTP Status 201")
    void sufficientBalanceWhenFeeIncludedExactly(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        // Balance of 10065 exactly covers 10000+65=10065 (0.65% fee)
        doReturn(10065).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given amount is 150 cents, " +
            "fee rounds to 1 (0.65% of 150 = 0.975), total amount is 151")
    void feeRoundsCorrectly(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(150);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        doReturn(200).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(151);
    }

    @Test
    @DisplayName("Given amount is $100,000 (10000000 cents), " +
            "fee is 65000 cents (0.65%), total amount is 10065000")
    void feeOnLargeAmount(TestInfo testInfo) {
        // Given
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(10000000);
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        doReturn(10065000).when(spyController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transaction).setAmount(10065000);
    }
}

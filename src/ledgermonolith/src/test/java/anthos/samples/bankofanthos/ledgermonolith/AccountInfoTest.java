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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AccountInfoTest {

    @Mock
    private Transaction transaction;

    private AccountInfo accountInfo;
    private Deque<Transaction> transactions;
    private static final Long BALANCE = 100L;

    @BeforeEach
    void setUp() {
        initMocks(this);
        transactions = new ArrayDeque<>();
        transactions.add(transaction);
        accountInfo = new AccountInfo(BALANCE, transactions);
    }

    @Test
    @DisplayName("Given AccountInfo is created, it should not be null")
    void constructorCreatesInstance() {
        // Then
        assertNotNull(accountInfo);
    }

    @Test
    @DisplayName("Given AccountInfo is created with a balance, getBalance returns that balance")
    void getBalanceReturnsCorrectValue() {
        // When
        final Long actualBalance = accountInfo.getBalance();

        // Then
        assertEquals(BALANCE, actualBalance);
    }

    @Test
    @DisplayName("Given AccountInfo is created with transactions, getTransactions returns them")
    void getTransactionsReturnsCorrectValue() {
        // When
        final Deque<Transaction> actualTransactions = accountInfo.getTransactions();

        // Then
        assertNotNull(actualTransactions);
        assertEquals(transactions, actualTransactions);
        assertEquals(1, actualTransactions.size());
    }
}

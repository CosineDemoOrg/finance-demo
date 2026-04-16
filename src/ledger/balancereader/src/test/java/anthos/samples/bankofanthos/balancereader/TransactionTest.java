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

package anthos.samples.bankofanthos.balancereader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("Given a transactionId is set, getTransactionId returns the correct value")
    void getTransactionId() throws Exception {
        // Given
        final long expectedId = 12345L;
        setField(transaction, "transactionId", expectedId);

        // When
        final long actualId = transaction.getTransactionId();

        // Then
        assertEquals(expectedId, actualId);
    }

    @Test
    @DisplayName("Given a fromAccountNum is set, getFromAccountNum returns the correct value")
    void getFromAccountNum() throws Exception {
        // Given
        final String expectedAccount = "1234567890";
        setField(transaction, "fromAccountNum", expectedAccount);

        // When
        final String actualAccount = transaction.getFromAccountNum();

        // Then
        assertEquals(expectedAccount, actualAccount);
    }

    @Test
    @DisplayName("Given a fromRoutingNum is set, getFromRoutingNum returns the correct value")
    void getFromRoutingNum() throws Exception {
        // Given
        final String expectedRouting = "987654321";
        setField(transaction, "fromRoutingNum", expectedRouting);

        // When
        final String actualRouting = transaction.getFromRoutingNum();

        // Then
        assertEquals(expectedRouting, actualRouting);
    }

    @Test
    @DisplayName("Given a toAccountNum is set, getToAccountNum returns the correct value")
    void getToAccountNum() throws Exception {
        // Given
        final String expectedAccount = "0987654321";
        setField(transaction, "toAccountNum", expectedAccount);

        // When
        final String actualAccount = transaction.getToAccountNum();

        // Then
        assertEquals(expectedAccount, actualAccount);
    }

    @Test
    @DisplayName("Given a toRoutingNum is set, getToRoutingNum returns the correct value")
    void getToRoutingNum() throws Exception {
        // Given
        final String expectedRouting = "111222333";
        setField(transaction, "toRoutingNum", expectedRouting);

        // When
        final String actualRouting = transaction.getToRoutingNum();

        // Then
        assertEquals(expectedRouting, actualRouting);
    }

    @Test
    @DisplayName("Given an amount is set, getAmount returns the correct value")
    void getAmount() throws Exception {
        // Given
        final Integer expectedAmount = 5000;
        setField(transaction, "amount", expectedAmount);

        // When
        final Integer actualAmount = transaction.getAmount();

        // Then
        assertEquals(expectedAmount, actualAmount);
    }

    @Test
    @DisplayName("Given a transaction with amount 1050 cents, toString formats as $10.50")
    void toStringFormat() throws Exception {
        // Given
        setField(transaction, "fromAccountNum", "1111");
        setField(transaction, "amount", 1050);
        setField(transaction, "toAccountNum", "2222");

        // When
        final String result = transaction.toString();

        // Then
        assertEquals("1111->$10.50->2222", result);
    }

    @Test
    @DisplayName("Given a transaction with zero amount, toString formats as $0.00")
    void toStringZeroAmount() throws Exception {
        // Given
        setField(transaction, "fromAccountNum", "1111");
        setField(transaction, "amount", 0);
        setField(transaction, "toAccountNum", "2222");

        // When
        final String result = transaction.toString();

        // Then
        assertEquals("1111->$0.00->2222", result);
    }
}

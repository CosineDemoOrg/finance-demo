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

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private Transaction transaction;

    private static final long TRANSACTION_ID = 12345L;
    private static final String FROM_ACCOUNT_NUM = "1111111111";
    private static final String FROM_ROUTING_NUM = "123456789";
    private static final String TO_ACCOUNT_NUM = "2222222222";
    private static final String TO_ROUTING_NUM = "987654321";
    private static final Integer AMOUNT = 1000;

    @BeforeEach
    void setUp() throws Exception {
        transaction = createTestTransaction();
    }

    @Test
    @DisplayName("Given a transaction with an ID, " +
            "getTransactionId returns the correct value")
    void getTransactionIdReturnsCorrectValue() {
        // When
        final long actualResult = transaction.getTransactionId();

        // Then
        assertEquals(TRANSACTION_ID, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with a from account number, " +
            "getFromAccountNum returns the correct value")
    void getFromAccountNumReturnsCorrectValue() {
        // When
        final String actualResult = transaction.getFromAccountNum();

        // Then
        assertEquals(FROM_ACCOUNT_NUM, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with a from routing number, " +
            "getFromRoutingNum returns the correct value")
    void getFromRoutingNumReturnsCorrectValue() {
        // When
        final String actualResult = transaction.getFromRoutingNum();

        // Then
        assertEquals(FROM_ROUTING_NUM, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with a to account number, " +
            "getToAccountNum returns the correct value")
    void getToAccountNumReturnsCorrectValue() {
        // When
        final String actualResult = transaction.getToAccountNum();

        // Then
        assertEquals(TO_ACCOUNT_NUM, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with a to routing number, " +
            "getToRoutingNum returns the correct value")
    void getToRoutingNumReturnsCorrectValue() {
        // When
        final String actualResult = transaction.getToRoutingNum();

        // Then
        assertEquals(TO_ROUTING_NUM, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with an amount, " +
            "getAmount returns the correct value")
    void getAmountReturnsCorrectValue() {
        // When
        final Integer actualResult = transaction.getAmount();

        // Then
        assertEquals(AMOUNT, actualResult);
    }

    @Test
    @DisplayName("Given a transaction with account numbers and amount, " +
            "toString formats correctly as fromAccount->$amount->toAccount")
    void toStringFormatsCorrectly() {
        // When
        final String actualResult = transaction.toString();

        // Then
        assertEquals("1111111111->$10.00->2222222222", actualResult);
    }

    @Test
    @DisplayName("Given a transaction with amount in cents, " +
            "toString formats cents correctly in dollar notation")
    void toStringFormatsCentsCorrectly() throws Exception {
        // Given
        setField(transaction, "amount", 1050);

        // When
        final String actualResult = transaction.toString();

        // Then
        assertEquals("1111111111->$10.50->2222222222", actualResult);
    }

    private Transaction createTestTransaction() throws Exception {
        Transaction t = new Transaction();
        setField(t, "transactionId", TRANSACTION_ID);
        setField(t, "fromAccountNum", FROM_ACCOUNT_NUM);
        setField(t, "fromRoutingNum", FROM_ROUTING_NUM);
        setField(t, "toAccountNum", TO_ACCOUNT_NUM);
        setField(t, "toRoutingNum", TO_ROUTING_NUM);
        setField(t, "amount", AMOUNT);
        return t;
    }

    private void setField(Object obj, String fieldName, Object value)
            throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
    }

    private void setField(Transaction txn, String fieldName, Object value)
            throws Exception {
        java.lang.reflect.Field field =
                Transaction.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(txn, value);
    }

    @Test
    @DisplayName("Given fields set via reflection, " +
            "getters return correct values")
    void gettersReturnCorrectValues() throws Exception {
        // Given
        setField(transaction, "fromAccountNum", "1111111111");
        setField(transaction, "fromRoutingNum", "222222222");
        setField(transaction, "toAccountNum", "3333333333");
        setField(transaction, "toRoutingNum", "444444444");
        setField(transaction, "amount", 500);

        // Then
        assertEquals("1111111111", transaction.getFromAccountNum());
        assertEquals("222222222", transaction.getFromRoutingNum());
        assertEquals("3333333333", transaction.getToAccountNum());
        assertEquals("444444444", transaction.getToRoutingNum());
        assertEquals(500, transaction.getAmount());
    }

    @Test
    @DisplayName("Given setAmount is called, " +
            "getAmount returns the updated value")
    void setAmountUpdatesAmount() {
        // When
        transaction.setAmount(500);

        // Then
        assertEquals(500, transaction.getAmount());

        // When
        transaction.setAmount(1000);

        // Then
        assertEquals(1000, transaction.getAmount());
    }

    @Test
    @DisplayName("Given requestUuid is null, " +
            "getRequestUuid returns empty string")
    void getRequestUuidReturnsEmptyStringWhenNull() {
        // Then
        assertEquals("", transaction.getRequestUuid());
    }

    @Test
    @DisplayName("Given requestUuid is set, " +
            "getRequestUuid returns the uuid")
    void getRequestUuidReturnsUuidWhenSet() throws Exception {
        // Given
        setField(transaction, "requestUuid", "test-uuid-123");

        // Then
        assertEquals("test-uuid-123", transaction.getRequestUuid());
    }

    @Test
    @DisplayName("Given account numbers and amount, " +
            "toString formats correctly as sender->$amount->receiver")
    void toStringFormatsCorrectly() throws Exception {
        // Given
        setField(transaction, "fromAccountNum", "1111111111");
        setField(transaction, "toAccountNum", "2222222222");
        setField(transaction, "amount", 12345);

        // Then
        assertEquals("1111111111->$123.45->2222222222",
                transaction.toString());
    }

    @Test
    @DisplayName("Given a whole dollar amount, " +
            "toString formats with two decimal places")
    void toStringFormatsWholeDollarAmount() throws Exception {
        // Given
        setField(transaction, "fromAccountNum", "1111111111");
        setField(transaction, "toAccountNum", "2222222222");
        setField(transaction, "amount", 500);

        // Then
        assertEquals("1111111111->$5.00->2222222222",
                transaction.toString());
    }

    @Test
    @DisplayName("Given a new Transaction, " +
            "transactionId returns default value of 0")
    void getTransactionIdReturnsDefault() {
        // Then
        assertEquals(0, transaction.getTransactionId());
    }
}

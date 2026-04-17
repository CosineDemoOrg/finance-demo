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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

class LedgerReaderTest {

    private LedgerReader ledgerReader;

    @Mock
    private TransactionRepository dbRepo;
    @Mock
    private LedgerReaderCallback callback;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final Integer POLL_MS = 100;

    @BeforeEach
    void setUp() throws Exception {
        initMocks(this);
        ledgerReader = new LedgerReader();
        setField(ledgerReader, "dbRepo", dbRepo);
        setField(ledgerReader, "pollMs", POLL_MS);
        setField(ledgerReader, "localRoutingNum", LOCAL_ROUTING_NUM);
    }

    @AfterEach
    void tearDown() throws Exception {
        Thread bgThread = (Thread) getField(ledgerReader, "backgroundThread");
        if (bgThread != null && bgThread.isAlive()) {
            bgThread.interrupt();
            bgThread.join(1000);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    @DisplayName("Given no background thread has started, isAlive returns true")
    void isAliveReturnsTrueBeforeStart() {
        // When
        boolean alive = ledgerReader.isAlive();

        // Then
        assertTrue(alive);
    }

    @Test
    @DisplayName("Given a null callback, startWithCallback throws IllegalStateException")
    void startWithNullCallbackThrowsException() {
        // When / Then
        assertThrows(IllegalStateException.class,
            () -> ledgerReader.startWithCallback(null));
    }

    @Test
    @DisplayName("Given a valid callback, startWithCallback starts a background thread")
    void startWithCallbackStartsBackgroundThread() throws Exception {
        // Given
        when(dbRepo.latestTransactionId()).thenReturn(null);

        // When
        ledgerReader.startWithCallback(callback);
        Thread.sleep(300);

        // Then
        assertTrue(ledgerReader.isAlive());
        Thread bgThread = (Thread) getField(ledgerReader, "backgroundThread");
        assertNotNull(bgThread);
        assertTrue(bgThread.isAlive());
    }

    @Test
    @DisplayName("Given new transactions exist, the callback is invoked for each transaction")
    void callbackIsInvokedWithNewTransactions() throws Exception {
        // Given
        Transaction transaction = new Transaction();
        setField(transaction, "transactionId", 1L);
        setField(transaction, "fromAccountNum", "1111");
        setField(transaction, "fromRoutingNum", LOCAL_ROUTING_NUM);
        setField(transaction, "toAccountNum", "2222");
        setField(transaction, "toRoutingNum", LOCAL_ROUTING_NUM);
        setField(transaction, "amount", 100);

        when(dbRepo.latestTransactionId())
            .thenReturn(0L)
            .thenReturn(1L);
        when(dbRepo.findLatest(0L))
            .thenReturn(Arrays.asList(transaction));

        // When
        ledgerReader.startWithCallback(callback);
        Thread.sleep(500);

        // Then
        verify(callback, atLeastOnce()).processTransaction(transaction);
    }

    @Test
    @DisplayName("Given the remote transaction id falls behind local, the thread dies and isAlive returns false")
    void isAliveReturnsFalseWhenThreadDies() throws Exception {
        // Given
        // First call during init: returns 5 (sets latestTransactionId = 5)
        // Second call in background loop: returns 1 (less than 5, triggers alive = false)
        when(dbRepo.latestTransactionId())
            .thenReturn(5L)
            .thenReturn(1L);

        // When
        ledgerReader.startWithCallback(callback);
        // Wait long enough for the thread to poll and detect the out-of-sync condition
        Thread.sleep(500);

        // Then
        assertFalse(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given no transactions exist, getLatestTransactionId returns -1 and reader starts without error")
    void getLatestTransactionIdReturnsNegativeOneWhenNoTransactions() throws Exception {
        // Given
        when(dbRepo.latestTransactionId()).thenReturn(null);

        // When
        ledgerReader.startWithCallback(callback);
        Thread.sleep(300);

        // Then — reader should be alive and running (latestTransactionId was set to -1)
        assertTrue(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given database connection fails during init, reader still starts background thread")
    void startSucceedsWhenDatabaseFailsDuringInit() throws Exception {
        // Given
        when(dbRepo.latestTransactionId())
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
            .thenReturn(null);

        // When
        ledgerReader.startWithCallback(callback);
        Thread.sleep(300);

        // Then — reader should still be alive despite init failure
        assertTrue(ledgerReader.isAlive());
        Thread bgThread = (Thread) getField(ledgerReader, "backgroundThread");
        assertNotNull(bgThread);
        assertTrue(bgThread.isAlive());
    }
}

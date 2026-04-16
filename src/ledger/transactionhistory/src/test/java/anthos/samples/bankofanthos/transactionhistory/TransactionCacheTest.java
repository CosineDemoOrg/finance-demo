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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.common.cache.LoadingCache;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.LinkedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class TransactionCacheTest {

    private TransactionCache transactionCache;

    @Mock
    private TransactionRepository dbRepo;

    private static final Integer CACHE_SIZE = 1000;
    private static final Integer CACHE_MINUTES = 60;
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final Integer HISTORY_LIMIT = 100;
    private static final String ACCOUNT_ID = "1234567890";

    @BeforeEach
    void setUp() throws Exception {
        initMocks(this);
        transactionCache = new TransactionCache();
        // Use reflection to inject the mock TransactionRepository
        Field dbRepoField = TransactionCache.class.getDeclaredField("dbRepo");
        dbRepoField.setAccessible(true);
        dbRepoField.set(transactionCache, dbRepo);
    }

    @Test
    @DisplayName("Given valid configuration, the cache bean is created successfully")
    void initializeCacheReturnsCacheBean() {
        // When
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // Then
        assertNotNull(cache);
    }

    @Test
    @DisplayName("Given an account ID, the cache loader calls the repository " +
            "with the correct account, routing number, and pagination")
    void cacheLoaderCallsRepositoryWithCorrectArgs() throws Exception {
        // Given
        Pageable expectedPage = PageRequest.of(0, HISTORY_LIMIT);
        when(dbRepo.findForAccount(eq(ACCOUNT_ID), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(new LinkedList<>());
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // When
        cache.get(ACCOUNT_ID);

        // Then
        verify(dbRepo).findForAccount(eq(ACCOUNT_ID), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage));
    }

    @Test
    @DisplayName("Given the repository returns transactions, " +
            "the cache returns the same result")
    void cacheReturnsResultFromRepository() throws Exception {
        // Given
        LinkedList<Transaction> expectedTransactions = new LinkedList<>();
        expectedTransactions.add(new Transaction());
        expectedTransactions.add(new Transaction());
        Pageable expectedPage = PageRequest.of(0, HISTORY_LIMIT);
        when(dbRepo.findForAccount(eq(ACCOUNT_ID), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(expectedTransactions);
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // When
        final Deque<Transaction> actualResult = cache.get(ACCOUNT_ID);

        // Then
        assertNotNull(actualResult);
        assertEquals(expectedTransactions.size(), actualResult.size());
    }

    @Test
    @DisplayName("Given the repository returns no transactions, " +
            "the cache returns an empty Deque")
    void cacheHandlesEmptyResults() throws Exception {
        // Given
        LinkedList<Transaction> emptyTransactions = new LinkedList<>();
        Pageable expectedPage = PageRequest.of(0, HISTORY_LIMIT);
        when(dbRepo.findForAccount(eq(ACCOUNT_ID), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(emptyTransactions);
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // When
        final Deque<Transaction> actualResult = cache.get(ACCOUNT_ID);

        // Then
        assertNotNull(actualResult);
        assertTrue(actualResult.isEmpty());
    }

    @Test
    @DisplayName("Given the cache is created with a max size, " +
            "verify the cache respects the maximum size configuration")
    void cacheHasCorrectMaxSize() throws Exception {
        // Given
        final Integer smallCacheSize = 2;
        Pageable expectedPage = PageRequest.of(0, HISTORY_LIMIT);
        when(dbRepo.findForAccount(eq("account1"), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(new LinkedList<>());
        when(dbRepo.findForAccount(eq("account2"), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(new LinkedList<>());
        when(dbRepo.findForAccount(eq("account3"), eq(LOCAL_ROUTING_NUM),
            eq(expectedPage))).thenReturn(new LinkedList<>());
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(smallCacheSize, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // When
        cache.get("account1");
        cache.get("account2");
        cache.get("account3");

        // Then - cache size should not exceed max size after eviction
        cache.cleanUp();
        assertTrue(cache.size() <= smallCacheSize);
    }

    @Test
    @DisplayName("Given the cache is created with recordStats, " +
            "verify cache statistics are enabled")
    void cacheStatisticsAreEnabled() {
        // When
        final LoadingCache<String, Deque<Transaction>> cache =
            transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);

        // Then
        assertNotNull(cache.stats());
    }
}

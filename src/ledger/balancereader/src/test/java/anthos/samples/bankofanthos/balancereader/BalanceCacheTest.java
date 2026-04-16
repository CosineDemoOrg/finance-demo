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

import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;

import com.google.common.util.concurrent.UncheckedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

class BalanceCacheTest {

    private BalanceCache balanceCache;

    @Mock
    private TransactionRepository dbRepo;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final Integer CACHE_SIZE = 100;

    @BeforeEach
    void setUp() throws Exception {
        initMocks(this);
        balanceCache = new BalanceCache();
        java.lang.reflect.Field field = BalanceCache.class.getDeclaredField("dbRepo");
        field.setAccessible(true);
        field.set(balanceCache, dbRepo);
    }

    @Test
    @DisplayName("Given valid parameters, initializeCache returns a non-null cache instance")
    void initializeCacheReturnsCacheInstance() {
        // When
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // Then
        assertNotNull(cache);
    }

    @Test
    @DisplayName("Given a cache miss, the cache loader calls the repository to find the balance")
    void cacheLoaderCallsRepository() throws Exception {
        // Given
        when(dbRepo.findBalance("testAcct", LOCAL_ROUTING_NUM)).thenReturn(100L);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        cache.get("testAcct");

        // Then
        verify(dbRepo).findBalance("testAcct", LOCAL_ROUTING_NUM);
    }

    @Test
    @DisplayName("Given the repository returns a balance, the cache returns that balance")
    void cacheReturnsBalanceFromRepository() throws Exception {
        // Given
        when(dbRepo.findBalance("testAcct", LOCAL_ROUTING_NUM)).thenReturn(500L);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        Long balance = cache.get("testAcct");

        // Then
        assertEquals(500L, balance);
    }

    @Test
    @DisplayName("Given the repository returns null, the cache returns 0")
    void cacheReturnsZeroWhenBalanceIsNull() throws Exception {
        // Given
        when(dbRepo.findBalance("testAcct", LOCAL_ROUTING_NUM)).thenReturn(null);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        Long balance = cache.get("testAcct");

        // Then
        assertEquals(0L, balance);
    }

    @Test
    @DisplayName("Given the repository throws ResourceAccessException, the cache propagates it")
    void cachePropagatesResourceAccessException() {
        // Given
        when(dbRepo.findBalance("testAcct", LOCAL_ROUTING_NUM))
            .thenThrow(new ResourceAccessException("connection refused"));
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When / Then
        UncheckedExecutionException thrown = assertThrows(UncheckedExecutionException.class,
            () -> cache.get("testAcct"));
        assertInstanceOf(ResourceAccessException.class, thrown.getCause());
    }

    @Test
    @DisplayName("Given the repository throws DataAccessResourceFailureException, the cache propagates it")
    void cachePropagatesDataAccessException() {
        // Given
        when(dbRepo.findBalance("testAcct", LOCAL_ROUTING_NUM))
            .thenThrow(new DataAccessResourceFailureException("db unavailable"));
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When / Then
        UncheckedExecutionException thrown = assertThrows(UncheckedExecutionException.class,
            () -> cache.get("testAcct"));
        assertInstanceOf(DataAccessResourceFailureException.class, thrown.getCause());
    }
}

// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package anthos.samples.bankofanthos.transactionhistory;

import java.util.LinkedList;
import java.util.List;
import java.util.Date;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository class for performing queries on the Transaction database
 */
@Repository
public interface TransactionRepository
        extends CrudRepository<Transaction, Long> {

    /**
     * Returns the id of the latest transaction, or NULL if none exist.
     */
    @Query("SELECT MAX(transactionId) FROM Transaction")
    Long latestTransactionId();

    @Query("SELECT t FROM Transaction t "
        + " WHERE (t.fromAccountNum=?1 AND t.fromRoutingNum=?2) "
        + "   OR (t.toAccountNum=?1 AND t.toRoutingNum=?2) "
        + " ORDER BY t.timestamp DESC")
    LinkedList<Transaction> findForAccount(String accountNum,
                                           String routingNum,
                                           Pageable pager);

    @Query("SELECT t FROM Transaction t "
        + " WHERE (t.fromAccountNum=?1 AND t.fromRoutingNum=?2) "
        + "   OR (t.toAccountNum=?1 AND t.toRouting);

    @Query("SELECT t FROM Transaction t "
        + " WHERE ((t.fromAccountNum=?1 AND t.fromRoutingNum=?2) "
        + "   OR (t.toAccountNum=?1 AND t.toRoutingNum=?2)) "
        + "   AND t.timestamp BETWEEN ?3 AND ?4 "
        + " ORDER BY t.timestamp DESC")
    List<Transaction> findForAccountBetween(String accountNum,
                                            String routingNum,
                                            Date from,
                                            Date to);

    @Query("SELECT t FROM Transaction t "
        + " WHERE ((t.fromAccountNum=?1 AND t.fromRoutingNum=?2) "
        + "   OR (t.toAccountNum=?1 AND t.toRoutingNum=?2)) "
        + "   AND t.timestamp >= ?3 "
        + " ORDER BY t.timestamp DESC")
    List<Transaction> findForAccountFrom(String accountNum,
                                         String routingNum,
                                         Date from);

    @Query("SELECT t FROM Transaction t "
        + " WHERE ((t.fromAccountNum=?1 AND t.fromRoutingNum=?2) "
        + "   OR (t.toAccountNum=?1 AND t.toRoutingNum=?2)) "
        + "   AND t.timestamp <= ?3 "
        + " ORDER BY t.timestamp DESC")
    List<Transaction> findForAccountTo(String accountNum,
                                       String routingNum,
                                       Date to);

    @Query("SELECT t FROM Transaction t "
        + " WHERE t.transactionId > ?1 ORDER BY t.transactionId ASC")
    List<Transaction> findLatest(long latestTransaction);
}

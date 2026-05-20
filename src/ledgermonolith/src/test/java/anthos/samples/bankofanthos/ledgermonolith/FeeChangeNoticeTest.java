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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying the 1.7% transaction fee rate in the ledger monolith.
 */
class FeeChangeNoticeTest {

    @Test
    @DisplayName("Transaction fee rate is set to 1.7%")
    void feeRateIsOnePercent() {
        assertEquals(0.017, LedgerMonolithController.TRANSACTION_FEE_RATE, 1e-9);
    }

    @Test
    @DisplayName("1.7% fee on $10 payment is 17 cents")
    void feeOnTenDollarsIsTenCents() {
        int amount = 1000; // $10 in cents
        int fee = (int) Math.round(amount * LedgerMonolithController.TRANSACTION_FEE_RATE);
        assertEquals(17, fee);
    }

    @Test
    @DisplayName("1.7% fee on $100 payment is $1.70")
    void feeOnOneHundredDollarsIsOneDollar() {
        int amount = 10000; // $100 in cents
        int fee = (int) Math.round(amount * LedgerMonolithController.TRANSACTION_FEE_RATE);
        assertEquals(170, fee);
    }

    @Test
    @DisplayName("1.7% fee on $1000 payment is $17.00")
    void feeOnOneThousandDollarsIsTenDollars() {
        int amount = 100000; // $1000 in cents
        int fee = (int) Math.round(amount * LedgerMonolithController.TRANSACTION_FEE_RATE);
        assertEquals(1700, fee);
    }
}

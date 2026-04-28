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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying the 1.5% transaction fee rate after the fee increase.
 * The fee was increased from 0.5% (0.005) to 1.5% (0.015).
 */
class FeeChangeNoticeTest {

    @Test
    @DisplayName("Transaction fee rate is set to 1.5%")
    void feeRateIsOnePointFivePercent() {
        assertEquals(0.015, LedgerWriterController.TRANSACTION_FEE_RATE, 1e-9);
    }

    @Test
    @DisplayName("Fee rate increased from previous 0.5% to new 1.5%")
    void feeRateIncreasedFromPreviousRate() {
        double previousRate = 0.005;
        assertTrue(LedgerWriterController.TRANSACTION_FEE_RATE > previousRate);
    }

    @Test
    @DisplayName("1.5% fee on $10 payment is 15 cents")
    void feeOnTenDollarsIsFifteenCents() {
        int amount = 1000; // $10 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(15, fee);
    }

    @Test
    @DisplayName("1.5% fee on $100 payment is $1.50")
    void feeOnOneHundredDollarsIsOneDollarFifty() {
        int amount = 10000; // $100 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(150, fee);
    }

    @Test
    @DisplayName("1.5% fee on $1000 payment is $15.00")
    void feeOnOneThousandDollarsIsFifteenDollars() {
        int amount = 100000; // $1000 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(1500, fee);
    }
}

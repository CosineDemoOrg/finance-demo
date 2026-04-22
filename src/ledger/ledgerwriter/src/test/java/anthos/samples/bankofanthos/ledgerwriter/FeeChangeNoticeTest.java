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
 * Tests verifying the 0.5% transaction fee rate after the fee reduction.
 * The fee was decreased from 1.1% (0.011) to 0.5% (0.005).
 */
class FeeChangeNoticeTest {

    @Test
    @DisplayName("Transaction fee rate is set to 0.5%")
    void feeRateIsZeroPointFivePercent() {
        assertEquals(0.005, LedgerWriterController.TRANSACTION_FEE_RATE, 1e-9);
    }

    @Test
    @DisplayName("Fee rate decreased from previous 1.1% to new 0.5%")
    void feeRateDecreasedFromPreviousRate() {
        double previousRate = 0.011;
        assertTrue(LedgerWriterController.TRANSACTION_FEE_RATE < previousRate);
    }

    @Test
    @DisplayName("0.5% fee on $10 payment is 5 cents")
    void feeOnTenDollarsIsTenPointNineCentsRoundedToEleven() {
        int amount = 1000; // $10 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(5, fee);
    }

    @Test
    @DisplayName("0.5% fee on $100 payment is 50 cents")
    void feeOnOneHundredDollarsIsOneDollarTen() {
        int amount = 10000;
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(50, fee);
    }

    @Test
    @DisplayName("0.5% fee on $1000 payment is $5.00")
    void feeOnOneThousandDollarsIsElevenDollars() {
        int amount = 100000;
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(500, fee);
    }
}

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
 * Tests verifying the 1.1% transaction fee rate introduced in the fee change notice.
 * The fee was increased from 0.7% (0.007) to 1.1% (0.011).
 */
class FeeChangeNoticeTest {

    @Test
    @DisplayName("Transaction fee rate is set to 1.1%")
    void feeRateIsOnePointOnePercent() {
        assertEquals(0.011, LedgerWriterController.TRANSACTION_FEE_RATE, 1e-9);
    }

    @Test
    @DisplayName("Fee rate increased from previous 0.7% to new 1.1%")
    void feeRateIncreasedFromPreviousRate() {
        double previousRate = 0.007;
        assertTrue(LedgerWriterController.TRANSACTION_FEE_RATE > previousRate);
    }

    @Test
    @DisplayName("1.1% fee on $10 payment is 11 cents")
    void feeOnTenDollarsIsTenPointNineCentsRoundedToEleven() {
        int amount = 1000; // $10 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(11, fee);
    }

    @Test
    @DisplayName("1.1% fee on $100 payment is $1.10")
    void feeOnOneHundredDollarsIsOneDollarTen() {
        int amount = 10000;
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(110, fee);
    }

    @Test
    @DisplayName("1.1% fee on $1000 payment is $11.00")
    void feeOnOneThousandDollarsIsElevenDollars() {
        int amount = 100000;
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(1100, fee);
    }
}

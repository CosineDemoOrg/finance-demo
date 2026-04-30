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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying the 0.65% transaction fee rate.
 */
class FeeChangeNoticeTest {

    @Test
    @DisplayName("Transaction fee rate is set to 0.65%")
    void feeRateIsZeroPointSixFivePercent() {
        assertEquals(0.0065, LedgerWriterController.TRANSACTION_FEE_RATE, 1e-9);
    }

    @Test
    @DisplayName("0.65% fee on $10 payment is 7 cents")
    void feeOnTenDollarsIsSevenCents() {
        int amount = 1000; // $10 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(7, fee);
    }

    @Test
    @DisplayName("0.65% fee on $100 payment is $0.65")
    void feeOnOneHundredDollarsIsSixtyFiveCents() {
        int amount = 10000; // $100 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(65, fee);
    }

    @Test
    @DisplayName("0.65% fee on $1000 payment is $6.50")
    void feeOnOneThousandDollarsIsSixFiftyDollars() {
        int amount = 100000; // $1000 in cents
        int fee = (int) Math.round(amount * LedgerWriterController.TRANSACTION_FEE_RATE);
        assertEquals(650, fee);
    }
}

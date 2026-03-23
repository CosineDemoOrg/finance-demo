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

package anthos.samples.bankofanthos.ledger.common;

public final class ExceptionMessages {
    private ExceptionMessages() {}

    public static final String EXCEPTION_MESSAGE_INVALID_NUMBER =
            "invalid account number or routing number";
    public static final String EXCEPTION_MESSAGE_NOT_AUTHENTICATED =
            "not authenticated";
    public static final String EXCEPTION_MESSAGE_SEND_TO_SELF =
            "can't send money to yourself";
    public static final String EXCEPTION_MESSAGE_INVALID_AMOUNT =
            "invalid transaction amount";
    public static final String EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION =
            "duplicate transaction";
    public static final String EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE =
            "insufficient balance";
    public static final String EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL =
            "Authorization header null";
}
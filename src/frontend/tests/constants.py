# Copyright 2021 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Example constants used in frontend tests
"""
import jwt
from Crypto.PublicKey import RSA


def generate_rsa_key():
    """Generate priv,pub key pair for test"""
    key = RSA.generate(2048)
    private_key = key.export_key()
    public_key = key.publickey().export_key()
    return private_key, public_key


EXAMPLE_PRIVATE_KEY, EXAMPLE_PUBLIC_KEY = generate_rsa_key()

EXAMPLE_USER = "testuser"
EXAMPLE_ACCOUNT = "1234567890"

EXAMPLE_USER_PAYLOAD = {
    "user": EXAMPLE_USER,
    "acct": EXAMPLE_ACCOUNT,
    "name": "Test User",
    "iat": 1000000000,
    "exp": 9999999999,
}

EXAMPLE_TOKEN = jwt.encode(
    EXAMPLE_USER_PAYLOAD, EXAMPLE_PRIVATE_KEY, algorithm="RS256"
)

EXAMPLE_HEADERS = {
    "Authorization": "Bearer " + EXAMPLE_TOKEN,
    "content-type": "application/json",
}

LOCAL_ROUTING = "123456789"

EXAMPLE_CONTACT = {
    "username": EXAMPLE_USER,
    "label": "Alice",
    "account_num": "9876543210",
    "routing_num": "987654321",
    "is_external": False,
}

EXAMPLE_CONTACT_2 = {
    "username": EXAMPLE_USER,
    "label": "Bob",
    "account_num": "5555555555",
    "routing_num": "555555555",
    "is_external": True,
}

EXAMPLE_TRANSACTION = {
    "fromAccountNum": EXAMPLE_ACCOUNT,
    "fromRoutingNum": LOCAL_ROUTING,
    "toAccountNum": "9876543210",
    "toRoutingNum": LOCAL_ROUTING,
    "amount": 100,
    "timestamp": "2021-06-01T12:00:00.000000+00:00",
}

EXAMPLE_TRANSACTION_INCOMING = {
    "fromAccountNum": "9876543210",
    "fromRoutingNum": LOCAL_ROUTING,
    "toAccountNum": EXAMPLE_ACCOUNT,
    "toRoutingNum": LOCAL_ROUTING,
    "amount": 200,
    "timestamp": "2021-07-15T08:30:00.000000+00:00",
}

EXAMPLE_BALANCE = {"balance": 50000}

# Env vars needed for create_app()
MOCKED_ENV_VARS = {
    "VERSION": "test-version",
    "LOCAL_ROUTING_NUM": LOCAL_ROUTING,
    "PUB_KEY_PATH": "/tmp/test_pub_key",
    "TRANSACTIONS_API_ADDR": "transactions:8080",
    "USERSERVICE_API_ADDR": "userservice:8080",
    "BALANCES_API_ADDR": "balances:8080",
    "HISTORY_API_ADDR": "history:8080",
    "CONTACTS_API_ADDR": "contacts:8080",
    "BACKEND_TIMEOUT": "4",
    "ENABLE_TRACING": "false",
    "SCHEME": "http",
}

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%S.%f%z"

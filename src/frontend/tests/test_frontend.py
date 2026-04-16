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

"""Tests for frontend service"""

import importlib.util
import json
import sys
import os
import unittest
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch, mock_open, MagicMock

# Add frontend source to path so local imports within frontend.py resolve
_frontend_dir = os.path.join(os.path.dirname(__file__), '..')
sys.path.insert(0, _frontend_dir)

from tests.constants import (
    EXAMPLE_PUBLIC_KEY,
    EXAMPLE_TOKEN,
    EXAMPLE_USER,
    EXAMPLE_ACCOUNT,
    EXAMPLE_USER_PAYLOAD,
    EXAMPLE_CONTACT,
    EXAMPLE_BALANCE,
    EXAMPLE_TRANSACTION,
    EXAMPLE_TRANSACTION_INCOMING,
    LOCAL_ROUTING,
    MOCKED_ENV_VARS,
    TIMESTAMP_FORMAT,
)


def _load_frontend_module():
    """Load frontend.py directly by file path to avoid __init__.py conflict."""
    frontend_path = os.path.join(_frontend_dir, 'frontend.py')
    spec = importlib.util.spec_from_file_location('frontend', frontend_path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules['frontend'] = mod
    spec.loader.exec_module(mod)
    return mod


class MockTracedThreadPoolExecutor(ThreadPoolExecutor):
    """Mock executor that ignores the tracer positional argument."""

    def __init__(self, tracer, *args, **kwargs):
        super().__init__(*args, **kwargs)


def _mock_api_get_side_effect(balance=None, transactions=None, contacts=None):
    """Return a side_effect for api_call.get that dispatches by URL."""
    if balance is None:
        balance = EXAMPLE_BALANCE
    if transactions is None:
        transactions = [EXAMPLE_TRANSACTION]
    if contacts is None:
        contacts = [EXAMPLE_CONTACT]

    def side_effect(**kwargs):
        url = kwargs.get('url', '')
        resp = MagicMock()
        if 'balances' in url:
            resp.json.return_value = balance
        elif 'transactions' in url:
            resp.json.return_value = transactions
        elif 'contacts' in url:
            resp.json.return_value = contacts
        return resp

    return side_effect


class TestFrontend(unittest.TestCase):
    """Test cases for frontend service"""

    def setUp(self):
        """Set up Flask test client with mocked dependencies."""
        real_open = open

        def _selective_open(path, *args, **kwargs):
            if isinstance(path, str) and path == MOCKED_ENV_VARS["PUB_KEY_PATH"]:
                return mock_open(read_data=EXAMPLE_PUBLIC_KEY.decode('utf-8'))()
            return real_open(path, *args, **kwargs)

        with patch("builtins.open", side_effect=_selective_open):
            with patch.dict("os.environ", MOCKED_ENV_VARS):
                with patch("requests.get") as mock_get:
                    mock_get.return_value.ok = False
                    frontend_mod = _load_frontend_module()
                    self.flask_app = frontend_mod.create_app()

        self.flask_app.config["TESTING"] = True
        self.flask_app.config["PUBLIC_KEY"] = EXAMPLE_PUBLIC_KEY
        self.test_app = self.flask_app.test_client()

    # --- Simple endpoint tests ---

    def test_version(self):
        """GET /version returns VERSION env var."""
        with patch.dict("os.environ", MOCKED_ENV_VARS):
            response = self.test_app.get('/version')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'test-version', response.data)

    def test_readiness(self):
        """GET /ready returns 200 with 'ok'."""
        response = self.test_app.get('/ready')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, b'ok')

    def test_whereami(self):
        """GET /whereami returns cluster/pod/zone info."""
        response = self.test_app.get('/whereami')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'Cluster:', response.data)
        self.assertIn(b'Pod:', response.data)
        self.assertIn(b'Zone:', response.data)

    # --- Root route tests ---

    @patch('frontend.render_template', return_value='rendered_login')
    def test_root_redirect_to_login(self, mock_render):
        """GET / without token renders login page."""
        response = self.test_app.get('/')
        self.assertEqual(response.status_code, 200)
        mock_render.assert_called_once()
        self.assertEqual(mock_render.call_args[0][0], 'login.html')

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_root_with_valid_token(self, mock_api_get, mock_render):
        """GET / with valid token renders home page."""
        mock_api_get.side_effect = _mock_api_get_side_effect()

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/')
        self.assertEqual(response.status_code, 200)
        mock_render.assert_called_once()
        self.assertEqual(mock_render.call_args[0][0], 'index.html')

    # --- Home route tests ---

    def test_home_no_token_redirects_to_login(self):
        """GET /home without token redirects to /login."""
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_home_with_valid_token(self, mock_api_get, mock_render):
        """GET /home with valid token renders index page with user data."""
        mock_api_get.side_effect = _mock_api_get_side_effect()

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)
        mock_render.assert_called_once()
        call_kwargs = mock_render.call_args[1]
        self.assertEqual(call_kwargs['account_id'], EXAMPLE_ACCOUNT)
        self.assertEqual(call_kwargs['name'], 'Test User')

    # --- Payment route tests ---

    def test_payment_no_token(self):
        """POST /payment without token returns 401."""
        response = self.test_app.post('/payment', data={
            'account_num': '9876543210',
            'amount': '10.00',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 401)

    @patch('frontend.sleep')
    @patch('frontend.requests.post')
    def test_payment_success(self, mock_post, mock_sleep):
        """POST /payment with valid data submits transaction and redirects."""
        mock_resp = MagicMock()
        mock_resp.status_code = 201
        mock_resp.raise_for_status.return_value = None
        mock_post.return_value = mock_resp

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.post('/payment', data={
            'account_num': '9876543210',
            'amount': '10.00',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 303)
        self.assertIn('/home', response.headers['Location'])

        # Verify fee calculation: gross=1000, fee=int(1000*0.005)=5, net=995
        call_data = json.loads(mock_post.call_args[1]['data'])
        gross = int(Decimal('10.00') * 100)
        fee = int(gross * Decimal('0.005'))
        self.assertEqual(call_data['amount'], gross - fee)

    @patch('frontend.requests.post')
    def test_payment_invalid_amount(self, mock_post):
        """POST /payment with non-numeric amount redirects with failure."""
        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.post('/payment', data={
            'account_num': '9876543210',
            'amount': 'not-a-number',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])

    # --- Deposit route tests ---

    def test_deposit_no_token(self):
        """POST /deposit without token returns 401."""
        response = self.test_app.post('/deposit', data={
            'account': json.dumps({"account_num": "1111111111", "routing_num": "111111111"}),
            'amount': '50.00',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 401)

    @patch('frontend.sleep')
    @patch('frontend.requests.post')
    def test_deposit_success(self, mock_post, mock_sleep):
        """POST /deposit with valid data submits transaction and redirects."""
        mock_resp = MagicMock()
        mock_resp.status_code = 201
        mock_resp.raise_for_status.return_value = None
        mock_post.return_value = mock_resp

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.post('/deposit', data={
            'account': json.dumps({"account_num": "1111111111", "routing_num": "111111111"}),
            'amount': '50.00',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 303)
        self.assertIn('/home', response.headers['Location'])

    @patch('frontend.requests.post')
    def test_deposit_invalid_routing(self, mock_post):
        """POST /deposit with local routing number fails with invalid routing."""
        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.post('/deposit', data={
            'account': 'add',
            'external_account_num': '1111111111',
            'external_routing_num': LOCAL_ROUTING,
            'amount': '50.00',
            'uuid': 'test-uuid',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])

    # --- Login route tests ---

    @patch('frontend.render_template', return_value='rendered_login')
    def test_login_page_get(self, mock_render):
        """GET /login without token renders login page."""
        response = self.test_app.get('/login')
        self.assertEqual(response.status_code, 200)
        mock_render.assert_called_once()
        self.assertEqual(mock_render.call_args[0][0], 'login.html')

    def test_login_page_get_already_authenticated(self):
        """GET /login with valid token redirects to /home."""
        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/login')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])

    @patch('frontend.requests.get')
    def test_login_post_success(self, mock_get):
        """POST /login with valid credentials sets token cookie and redirects."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status.return_value = None
        mock_resp.json.return_value = {'token': EXAMPLE_TOKEN}
        mock_get.return_value = mock_resp

        response = self.test_app.post('/login', data={
            'username': EXAMPLE_USER,
            'password': 'password',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])
        # Verify token cookie is set
        cookie_header = response.headers.getlist('Set-Cookie')
        cookie_set = any('token=' in c for c in cookie_header)
        self.assertTrue(cookie_set)

    @patch('frontend.requests.get')
    def test_login_post_failure(self, mock_get):
        """POST /login with invalid credentials redirects to /login."""
        from requests.exceptions import RequestException
        mock_get.side_effect = RequestException("login failed")

        response = self.test_app.post('/login', data={
            'username': 'baduser',
            'password': 'badpass',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])

    # --- Signup route tests ---

    @patch('frontend.render_template', return_value='rendered_signup')
    def test_signup_page_get(self, mock_render):
        """GET /signup without token renders signup page."""
        response = self.test_app.get('/signup')
        self.assertEqual(response.status_code, 200)
        mock_render.assert_called_once()
        self.assertEqual(mock_render.call_args[0][0], 'signup.html')

    def test_signup_page_get_already_authenticated(self):
        """GET /signup with valid token redirects to /home."""
        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/signup')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])

    @patch('frontend.requests.get')
    @patch('frontend.requests.post')
    def test_signup_post_success(self, mock_post, mock_get):
        """POST /signup creates user and logs in."""
        mock_post_resp = MagicMock()
        mock_post_resp.status_code = 201
        mock_post.return_value = mock_post_resp

        mock_get_resp = MagicMock()
        mock_get_resp.status_code = 200
        mock_get_resp.raise_for_status.return_value = None
        mock_get_resp.json.return_value = {'token': EXAMPLE_TOKEN}
        mock_get.return_value = mock_get_resp

        response = self.test_app.post('/signup', data={
            'username': 'newuser',
            'password': 'newpass',
            'password-repeat': 'newpass',
            'firstname': 'New',
            'lastname': 'User',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/home', response.headers['Location'])

    @patch('frontend.requests.post')
    def test_signup_post_failure(self, mock_post):
        """POST /signup with service failure redirects to login with error."""
        from requests.exceptions import RequestException
        mock_post.side_effect = RequestException("service down")

        response = self.test_app.post('/signup', data={
            'username': 'newuser',
            'password': 'newpass',
        })
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])

    # --- Logout test ---

    def test_logout(self):
        """POST /logout clears cookie and redirects to login."""
        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.post('/logout')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])
        # Token cookie should be expired
        cookie_header = response.headers.getlist('Set-Cookie')
        token_cleared = any(
            'token=' in c and ('Expires=' in c or 'Max-Age=0' in c)
            for c in cookie_header
        )
        self.assertTrue(token_cleared)

    # --- Token verification tests (via routes) ---

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_verify_token_valid(self, mock_api_get, mock_render):
        """Valid token grants access to authenticated route."""
        mock_api_get.side_effect = _mock_api_get_side_effect()

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)

    def test_verify_token_invalid(self):
        """Invalid token is rejected and redirects to login."""
        self.test_app.set_cookie('token', 'invalid-token-string')
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])

    def test_verify_token_none(self):
        """Missing token redirects to login."""
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 302)
        self.assertIn('/login', response.headers['Location'])

    # --- Decode token test (via route behavior) ---

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_decode_token(self, mock_api_get, mock_render):
        """decode_token correctly extracts user info from token."""
        mock_api_get.side_effect = _mock_api_get_side_effect()

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)

        call_kwargs = mock_render.call_args[1]
        self.assertEqual(call_kwargs['name'], 'Test User')
        self.assertEqual(call_kwargs['account_id'], EXAMPLE_ACCOUNT)

    # --- Template formatter tests ---

    def test_format_currency_positive(self):
        """format_currency(12345) returns '$123.45'."""
        fmt = self.flask_app.jinja_env.globals['format_currency']
        self.assertEqual(fmt(12345), '$123.45')

    def test_format_currency_negative(self):
        """format_currency(-12345) returns '-$123.45'."""
        fmt = self.flask_app.jinja_env.globals['format_currency']
        self.assertEqual(fmt(-12345), '-$123.45')

    def test_format_currency_none(self):
        """format_currency(None) returns '$---'."""
        fmt = self.flask_app.jinja_env.globals['format_currency']
        self.assertEqual(fmt(None), '$---')

    def test_format_currency_zero(self):
        """format_currency(0) returns '$0.00'."""
        fmt = self.flask_app.jinja_env.globals['format_currency']
        self.assertEqual(fmt(0), '$0.00')

    def test_format_timestamp_day(self):
        """format_timestamp_day extracts day as two-digit string."""
        fmt = self.flask_app.jinja_env.globals['format_timestamp_day']
        self.assertEqual(fmt('2021-06-01T12:00:00.000000+00:00'), '01')

    def test_format_timestamp_month(self):
        """format_timestamp_month extracts abbreviated month name."""
        fmt = self.flask_app.jinja_env.globals['format_timestamp_month']
        self.assertEqual(fmt('2021-06-01T12:00:00.000000+00:00'), 'Jun')

    # --- Contact label population tests (via home route) ---

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_populate_contact_labels_outgoing(self, mock_api_get, mock_render):
        """Outgoing transaction gets contact label from matching contact."""
        transaction = {
            "fromAccountNum": EXAMPLE_ACCOUNT,
            "fromRoutingNum": LOCAL_ROUTING,
            "toAccountNum": "9876543210",
            "toRoutingNum": LOCAL_ROUTING,
            "amount": 100,
            "timestamp": "2021-06-01T12:00:00.000000+00:00",
        }
        contact = {
            "account_num": "9876543210",
            "label": "Alice",
        }

        def get_side_effect(**kwargs):
            url = kwargs.get('url', '')
            resp = MagicMock()
            if 'balances' in url:
                resp.json.return_value = EXAMPLE_BALANCE
            elif 'transactions' in url:
                resp.json.return_value = [transaction]
            elif 'contacts' in url:
                resp.json.return_value = [contact]
            return resp

        mock_api_get.side_effect = get_side_effect

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)

        call_kwargs = mock_render.call_args[1]
        history = call_kwargs.get('history')
        if history:
            self.assertEqual(history[0].get('accountLabel'), 'Alice')

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_populate_contact_labels_incoming(self, mock_api_get, mock_render):
        """Incoming transaction gets contact label from matching contact."""
        transaction = {
            "fromAccountNum": "9876543210",
            "fromRoutingNum": LOCAL_ROUTING,
            "toAccountNum": EXAMPLE_ACCOUNT,
            "toRoutingNum": LOCAL_ROUTING,
            "amount": 200,
            "timestamp": "2021-07-15T08:30:00.000000+00:00",
        }
        contact = {
            "account_num": "9876543210",
            "label": "Alice",
        }

        def get_side_effect(**kwargs):
            url = kwargs.get('url', '')
            resp = MagicMock()
            if 'balances' in url:
                resp.json.return_value = EXAMPLE_BALANCE
            elif 'transactions' in url:
                resp.json.return_value = [transaction]
            elif 'contacts' in url:
                resp.json.return_value = [contact]
            return resp

        mock_api_get.side_effect = get_side_effect

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)

        call_kwargs = mock_render.call_args[1]
        history = call_kwargs.get('history')
        if history:
            self.assertEqual(history[0].get('accountLabel'), 'Alice')

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_populate_contact_labels_no_match(self, mock_api_get, mock_render):
        """Transaction with no matching contact gets None label."""
        transaction = {
            "fromAccountNum": EXAMPLE_ACCOUNT,
            "fromRoutingNum": LOCAL_ROUTING,
            "toAccountNum": "0000000000",
            "toRoutingNum": LOCAL_ROUTING,
            "amount": 100,
            "timestamp": "2021-06-01T12:00:00.000000+00:00",
        }
        contact = {
            "account_num": "9876543210",
            "label": "Alice",
        }

        def get_side_effect(**kwargs):
            url = kwargs.get('url', '')
            resp = MagicMock()
            if 'balances' in url:
                resp.json.return_value = EXAMPLE_BALANCE
            elif 'transactions' in url:
                resp.json.return_value = [transaction]
            elif 'contacts' in url:
                resp.json.return_value = [contact]
            return resp

        mock_api_get.side_effect = get_side_effect

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)

        call_kwargs = mock_render.call_args[1]
        history = call_kwargs.get('history')
        if history:
            self.assertIsNone(history[0].get('accountLabel'))

    @patch('frontend.render_template', return_value='rendered_home')
    @patch('frontend.TracedThreadPoolExecutor', MockTracedThreadPoolExecutor)
    @patch('api_call.get')
    def test_populate_contact_labels_none_params(self, mock_api_get, mock_render):
        """_populate_contact_labels handles None transactions gracefully."""

        def get_side_effect(**kwargs):
            url = kwargs.get('url', '')
            resp = MagicMock()
            if 'balances' in url:
                resp.json.return_value = EXAMPLE_BALANCE
            elif 'transactions' in url:
                return None
            elif 'contacts' in url:
                resp.json.return_value = []
            return resp

        mock_api_get.side_effect = get_side_effect

        self.test_app.set_cookie('token', EXAMPLE_TOKEN)
        response = self.test_app.get('/home')
        self.assertEqual(response.status_code, 200)


if __name__ == '__main__':
    unittest.main()

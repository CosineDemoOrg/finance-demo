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

"""Tests for api_call module"""

import sys
import os
import unittest
from unittest.mock import patch, MagicMock
import logging

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from api_call import ApiCall, ApiRequest


class TestApiRequest(unittest.TestCase):
    """Test cases for ApiRequest"""

    def test_init(self):
        """ApiRequest stores url, headers, and timeout."""
        req = ApiRequest(url='http://test.com/api', headers={'Auth': 'token'}, timeout=5)
        self.assertEqual(req.url, 'http://test.com/api')
        self.assertEqual(req.headers, {'Auth': 'token'})
        self.assertEqual(req.timeout, 5)


class TestApiCall(unittest.TestCase):
    """Test cases for ApiCall"""

    def setUp(self):
        self.logger = logging.getLogger('test')
        self.api_request = ApiRequest(
            url='http://test.com/api',
            headers={'Authorization': 'Bearer token'},
            timeout=4
        )

    def test_init(self):
        """ApiCall stores display_name, api_request, and logger."""
        api_call = ApiCall(
            display_name='test_call',
            api_request=self.api_request,
            logger=self.logger
        )
        self.assertEqual(api_call.display_name, 'test_call')
        self.assertEqual(api_call.api_request, self.api_request)

    @patch('api_call.get')
    def test_make_call_success(self, mock_get):
        """Successful API call returns the response."""
        mock_response = MagicMock()
        mock_response.json.return_value = {'balance': 1000}
        mock_get.return_value = mock_response

        api_call = ApiCall(
            display_name='balance',
            api_request=self.api_request,
            logger=self.logger
        )
        result = api_call.make_call()

        self.assertIsNotNone(result)
        mock_get.assert_called_once_with(
            url=self.api_request.url,
            headers=self.api_request.headers,
            timeout=self.api_request.timeout
        )

    @patch('api_call.get')
    def test_make_call_request_exception(self, mock_get):
        """API call returns None on RequestException."""
        from requests.exceptions import RequestException
        mock_get.side_effect = RequestException("connection failed")

        api_call = ApiCall(
            display_name='balance',
            api_request=self.api_request,
            logger=self.logger
        )
        result = api_call.make_call()

        self.assertIsNone(result)

    @patch('api_call.get')
    def test_make_call_value_error(self, mock_get):
        """API call returns None on ValueError."""
        mock_get.side_effect = ValueError("bad value")

        api_call = ApiCall(
            display_name='balance',
            api_request=self.api_request,
            logger=self.logger
        )
        result = api_call.make_call()

        self.assertIsNone(result)

    @patch('api_call.get')
    def test_make_call_returns_response(self, mock_get):
        """make_call returns the full response object."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = [{"id": 1}]
        mock_get.return_value = mock_response

        api_call = ApiCall(
            display_name='transactions',
            api_request=self.api_request,
            logger=self.logger
        )
        result = api_call.make_call()

        self.assertEqual(result, mock_response)
        self.assertEqual(result.json(), [{"id": 1}])


if __name__ == '__main__':
    unittest.main()

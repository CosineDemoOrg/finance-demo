# Copyright 2026

import unittest
from unittest.mock import patch, mock_open, Mock

from frontend.frontend import create_app


class TestExportCsv(unittest.TestCase):

    def setUp(self):
        with patch('frontend.frontend.open', mock_open(read_data='key')):
            with patch(
                'os.environ',
                {
                    'VERSION': '1',
                    'ENABLE_TRACING': 'false',
                    'PUB_KEY_PATH': '1',
                    'LOCAL_ROUTING_NUM': '123456789',
                    'TRANSACTIONS_API_ADDR': 'ledgerwriter:8080',
                    'USERSERVICE_API_ADDR': 'userservice:8080',
                    'BALANCES_API_ADDR': 'balancereader:8080',
                    'HISTORY_API_ADDR': 'transactionhistory:8080',
                    'CONTACTS_API_ADDR': 'contacts:8080',
                    'SCHEME': 'http',
                    'BACKEND_TIMEOUT': '1',
                },
            ):
                with patch('frontend.frontend.requests.get') as mock_get:
                    mock_resp = Mock()
                    mock_resp.ok = False
                    mock_get.return_value = mock_resp
                    self.flask_app = create_app()
                    self.flask_app.config['TESTING'] = True
                    self.test_app = self.flask_app.test_client()

    @patch('frontend.frontend.requests.get')
    @patch('frontend.frontend.jwt.decode')
    def test_export_csv_downloads_from_backend(self, mock_jwt_decode, mock_requests_get):
        def decode_side_effect(*_args, **kwargs):
            if kwargs.get('options', {}).get('verify_signature') is False:
                return {'acct': '1234567890'}
            return {'acct': '1234567890'}

        mock_jwt_decode.side_effect = decode_side_effect

        backend_resp = Mock()
        backend_resp.content = b'date,description,amount,currency,balance_after,transaction_id\n'
        backend_resp.headers = {'Content-Disposition': 'attachment; filename=test.csv'}
        backend_resp.raise_for_status = Mock()
        mock_requests_get.return_value = backend_resp

        resp = self.test_app.get(
            '/transactions/export?from=2024-01-01&to=2024-01-31',
            headers={'Cookie': 'token=abc'},
        )

        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.headers['Content-Type'], 'text/csv')
        self.assertEqual(resp.headers['Content-Disposition'], 'attachment; filename=test.csv')
        self.assertTrue(resp.data.startswith(b'date,description'))


if __name__ == '__main__':
    unittest.main()

# Copyright 2024
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
Unit tests for notification providers.
"""

import os
import unittest
from unittest.mock import MagicMock, patch

from userservice.notifications import (
    ConsoleNotificationProvider,
    SmtpNotificationProvider,
)


class TestConsoleNotificationProvider(unittest.TestCase):
    """Tests for the console notification provider."""

    def setUp(self):
        self.logger = MagicMock()
        self.provider = ConsoleNotificationProvider(self.logger)

    def test_send_welcome_email_logs_message(self):
        """Console provider should log welcome email details."""
        self.provider.send_welcome_email("user@example.com", "Test User")

        self.logger.info.assert_called_with(
            "ConsoleNotificationProvider: welcome email to %s (%s)",
            "user@example.com",
            "Test User",
        )

    def test_send_password_reset_email_logs_message(self):
        """Console provider should log reset email details."""
        self.provider.send_password_reset_email("user@example.com", "https://reset")

        self.logger.info.assert_called_with(
            "ConsoleNotificationProvider: password reset email to %s with link %s",
            "user@example.com",
            "https://reset",
        )


class TestSmtpNotificationProvider(unittest.TestCase):
    """Tests for the SMTP notification provider."""

    def setUp(self):
        # Ensure we start from a clean environment for each test
        self.env_patcher = patch.dict(os.environ, {}, clear=True)
        self.env_patcher.start()
        self.logger = MagicMock()

    def tearDown(self):
        self.env_patcher.stop()

    def test_from_env_requires_host_and_from_address(self):
        """from_env should validate required configuration."""
        with self.assertRaises(ValueError):
            SmtpNotificationProvider.from_env(self.logger)

        os.environ["SMTP_HOST"] = "smtp.example.com"
        with self.assertRaises(ValueError):
            SmtpNotificationProvider.from_env(self.logger)

    @patch("userservice.notifications.smtplib.SMTP")
    def test_send_welcome_email_uses_smtp(self, mock_smtp_cls):
        """SMTP provider should send a welcome email using smtplib."""
        os.environ["SMTP_HOST"] = "smtp.example.com"
        os.environ["SMTP_FROM_ADDRESS"] = "no-reply@example.com"
        os.environ["SMTP_PORT"] = "1025"
        os.environ["SMTP_USERNAME"] = "user"
        os.environ["SMTP_PASSWORD"] = "pass"
        os.environ["SMTP_USE_TLS"] = "true"

        provider = SmtpNotificationProvider.from_env(self.logger)

        mock_smtp = MagicMock()
        mock_smtp_cls.return_value.__enter__.return_value = mock_smtp

        provider.send_welcome_email("recipient@example.com", "Test User")

        mock_smtp_cls.assert_called_with("smtp.example.com", 1025)
        mock_smtp.starttls.assert_called_once()
        mock_smtp.login.assert_called_once_with("user", "pass")
        mock_smtp.send_message.assert_called_once()

    @patch("userservice.notifications.smtplib.SMTP")
    def test_send_password_reset_email_without_auth(self, mock_smtp_cls):
        """SMTP provider should work without authentication if credentials are absent."""
        os.environ["SMTP_HOST"] = "smtp.example.com"
        os.environ["SMTP_FROM_ADDRESS"] = "no-reply@example.com"
        os.environ["SMTP_PORT"] = "25"
        os.environ["SMTP_USE_TLS"] = "false"

        provider = SmtpNotificationProvider.from_env(self.logger)

        mock_smtp = MagicMock()
        mock_smtp_cls.return_value.__enter__.return_value = mock_smtp

        provider.send_password_reset_email(
            "recipient@example.com", "https://reset-link"
        )

        mock_smtp_cls.assert_called_with("smtp.example.com", 25)
        mock_smtp.starttls.assert_not_called()
        mock_smtp.login.assert_not_called()
        mock_smtp.send_message.assert_called_once()
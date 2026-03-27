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
Notification service and providers used by userservice.

This module defines a simple abstraction for sending user-facing
notifications (e.g. welcome and password reset emails) with pluggable
providers. The concrete provider is selected at runtime via
environment variables.
"""

import os
import smtplib
from abc import ABC, abstractmethod
from email.message import EmailMessage


class NotificationProvider(ABC):
    """Abstract base class for notification providers."""

    @abstractmethod
    def send_welcome_email(self, recipient, full_name):
        """Send a welcome email to the given recipient."""
        raise NotImplementedError

    @abstractmethod
    def send_password_reset_email(self, recipient, reset_link):
        """Send a password reset email to the given recipient."""
        raise NotImplementedError


class ConsoleNotificationProvider(NotificationProvider):
    """Notification provider that only logs messages."""

    def __init__(self, logger):
        self._logger = logger

    def send_welcome_email(self, recipient, full_name):
        self._logger.info(
            "ConsoleNotificationProvider: welcome email to %s (%s)",
            recipient,
            full_name,
        )

    def send_password_reset_email(self, recipient, reset_link):
        self._logger.info(
            "ConsoleNotificationProvider: password reset email to %s with link %s",
            recipient,
            reset_link,
        )


class SmtpNotificationProvider(NotificationProvider):
    """Notification provider that sends emails using SMTP."""

    def __init__(self, host, port, username, password, use_tls, from_address, logger):
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._use_tls = use_tls
        self._from_address = from_address
        self._logger = logger

    @classmethod
    def from_env(cls, logger):
        """Create an SMTP provider instance from environment variables."""
        host = os.getenv("SMTP_HOST")
        from_address = os.getenv("SMTP_FROM_ADDRESS")

        if not host:
            raise ValueError("SMTP_HOST environment variable must be set for SMTP provider")
        if not from_address:
            raise ValueError(
                "SMTP_FROM_ADDRESS environment variable must be set for SMTP provider"
            )

        port = int(os.getenv("SMTP_PORT", "587"))
        username = os.getenv("SMTP_USERNAME")
        password = os.getenv("SMTP_PASSWORD")
        use_tls = os.getenv("SMTP_USE_TLS", "true").lower() in {"1", "true", "yes"}

        return cls(
            host=host,
            port=port,
            username=username,
            password=password,
            use_tls=use_tls,
            from_address=from_address,
            logger=logger,
        )

    def send_welcome_email(self, recipient, full_name):
        subject = "Welcome to Bank of Anthos"
        body = (
            "Hi {name},\n\n"
            "Welcome to Bank of Anthos. Your account has been created successfully.\n\n"
            "If you did not sign up for this account, please contact support.\n"
        ).format(name=full_name)
        self._send_email(recipient, subject, body)

    def send_password_reset_email(self, recipient, reset_link):
        subject = "Reset your Bank of Anthos password"
        body = (
            "We received a request to reset your Bank of Anthos password.\n\n"
            "To reset your password, visit the following link:\n"
            "{link}\n\n"
            "If you did not request a password reset, you can ignore this email.\n"
        ).format(link=reset_link)
        self._send_email(recipient, subject, body)

    def _send_email(self, recipient, subject, body):
        msg = EmailMessage()
        msg["From"] = self._from_address
        msg["To"] = recipient
        msg["Subject"] = subject
        msg.set_content(body)

        self._logger.debug(
            "SmtpNotificationProvider: sending email to %s via %s:%s",
            recipient,
            self._host,
            self._port,
        )

        with smtplib.SMTP(self._host, self._port) as server:
            if self._use_tls:
                server.starttls()
            if self._username:
                server.login(self._username, self._password or "")
            server.send_message(msg)

        self._logger.info(
            "SmtpNotificationProvider: email sent to %s with subject '%s'",
            recipient,
            subject,
        )


class NotificationsService:
    """
    Simple notifications service that delegates to a concrete provider.

    The provider is selected using the NOTIFICATIONS_PROVIDER environment
    variable. Supported values:
      - "console" (default)
      - "smtp"
    """

    def __init__(self, logger):
        self._logger = logger
        provider_name = os.getenv("NOTIFICATIONS_PROVIDER", "console").lower()

        if provider_name == "smtp":
            try:
                self._provider = SmtpNotificationProvider.from_env(logger)
                self._logger.info("NotificationsService: using SMTP provider")
            except Exception as err:  # pylint: disable=broad-except
                # If SMTP configuration is invalid, fall back to console provider
                self._logger.error(
                    "NotificationsService: failed to initialise SMTP provider (%s). "
                    "Falling back to console provider.",
                    err,
                )
                self._provider = ConsoleNotificationProvider(logger)
        else:
            self._logger.info("NotificationsService: using console provider")
            self._provider = ConsoleNotificationProvider(logger)

    def send_welcome_email(self, recipient, full_name):
        try:
            self._provider.send_welcome_email(recipient, full_name)
        except Exception as err:  # pylint: disable=broad-except
            # Email failures should not break core user flows.
            self._logger.error(
                "NotificationsService: error sending welcome email to %s: %s",
                recipient,
                err,
            )

    def send_password_reset_email(self, recipient, reset_link):
        try:
            self._provider.send_password_reset_email(recipient, reset_link)
        except Exception as err:  # pylint: disable=broad-except
            self._logger.error(
                "NotificationsService: error sending password reset email to %s: %s",
                recipient,
                err,
            )
# Copyright 2019 Google LLC
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
db manages interactions with the underlying database
"""

import logging
import random
from sqlalchemy import create_engine, MetaData, Table, Column, String, Date, LargeBinary, Integer, Text, ForeignKey, DateTime, func, select
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor

class UserDb:
    """
    UserDb provides a set of helper functions over SQLAlchemy
    to handle db operations for userservice
    """

    def __init__(self, uri, logger=logging):
        self.engine = create_engine(uri)
        self.logger = logger
        metadata = MetaData(self.engine)

        self.users_table = Table(
            'users',
            metadata,
            Column('accountid', String, primary_key=True),
            Column('username', String, unique=True, nullable=False),
            Column('passhash', LargeBinary, nullable=False),
            Column('firstname', String, nullable=False),
            Column('lastname', String, nullable=False),
            Column('birthday', Date, nullable=False),
            Column('timezone', String, nullable=False),
            Column('address', String, nullable=False),
            Column('state', String, nullable=False),
            Column('zip', String, nullable=False),
            Column('ssn', String, nullable=False),
        )

        self.organizations_table = Table(
            'organizations',
            metadata,
            Column('id', Integer, primary_key=True),
            Column('name', String, nullable=False),
            Column('created_at', DateTime, server_default=func.now(), nullable=False),
        )

        self.memberships_table = Table(
            'organization_memberships',
            metadata,
            Column('id', Integer, primary_key=True),
            Column('org_id', Integer, ForeignKey('organizations.id'), nullable=False),
            Column('accountid', String, ForeignKey('users.accountid'), nullable=False),
            Column('role', String, nullable=False),
        )

        self.items_table = Table(
            'items',
            metadata,
            Column('id', Integer, primary_key=True),
            Column('org_id', Integer, ForeignKey('organizations.id'), nullable=False),
            Column('owner_accountid', String, ForeignKey('users.accountid'), nullable=False),
            Column('name', String, nullable=False),
            Column('description', Text),
            Column('created_at', DateTime, server_default=func.now(), nullable=False),
        )

        # Set up tracing autoinstrumentation for sqlalchemy
        SQLAlchemyInstrumentor().instrument(
            engine=self.engine,
            service='users',
        )

    def add_user(self, user):
        """Add a user to the database.

        Params: user - a key/value dict of attributes describing a new user
                    {'username': username, 'password': password, ...}
        Raises: SQLAlchemyError if there was an issue with the database
        """
        statement = self.users_table.insert().values(user)
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            conn.execute(statement)

    def generate_accountid(self):
        """Generates a globally unique alphanumerical accountid."""
        self.logger.debug('Generating an account ID')
        accountid = None
        with self.engine.connect() as conn:
            while accountid is None:
                accountid = str(random.randint(1_000_000_000, (10_000_000_000 - 1)))

                statement = self.users_table.select().where(
                    self.users_table.c.accountid == accountid
                )
                self.logger.debug('QUERY: %s', str(statement))
                result = conn.execute(statement).first()
                # If there already exists an account, try again.
                if result is not None:
                    accountid = None
                    self.logger.debug('RESULT: account ID already exists. Trying again')
        self.logger.debug('RESULT: account ID generated.')
        return accountid

    def get_user(self, username):
        """Get user data for the specified username.

        Params: username - the username of the user
        Return: a key/value dict of user attributes,
                {'username': username, 'accountid': accountid, ...}
                or None if that user does not exist
        Raises: SQLAlchemyError if there was an issue with the database
        """
        statement = self.users_table.select().where(self.users_table.c.username == username)
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            result = conn.execute(statement).first()
        self.logger.debug('RESULT: fetched user data for %s', username)
        return dict(result) if result is not None else None

    def get_memberships_for_account(self, accountid):
        """Return a list of organizations the given account belongs to."""
        statement = (
            select(
                self.organizations_table.c.id.label('org_id'),
                self.organizations_table.c.name.label('org_name'),
                self.memberships_table.c.role.label('role'),
            )
            .select_from(self.memberships_table.join(
                self.organizations_table,
                self.organizations_table.c.id == self.memberships_table.c.org_id,
            ))
            .where(self.memberships_table.c.accountid == accountid)
        )
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            rows = conn.execute(statement).fetchall()
        return [dict(row) for row in rows]

    def get_membership(self, org_id, accountid):
        """Return membership row for the given org/account or None."""
        statement = self.memberships_table.select().where(
            (self.memberships_table.c.org_id == org_id)
            & (self.memberships_table.c.accountid == accountid)
        )
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            row = conn.execute(statement).first()
        return dict(row) if row is not None else None

    def create_organization(self, name, owner_accountid):
        """Create an organization and make the owner an admin."""
        org_insert = (
            self.organizations_table.insert()
            .values(name=name)
            .returning(self.organizations_table.c.id, self.organizations_table.c.name)
        )
        self.logger.debug('QUERY: %s', str(org_insert))
        with self.engine.connect() as conn:
            org_row = conn.execute(org_insert).first()
            membership_insert = self.memberships_table.insert().values(
                org_id=org_row.id,
                accountid=owner_accountid,
                role='admin',
            )
            self.logger.debug('QUERY: %s', str(membership_insert))
            conn.execute(membership_insert)
        return {'org_id': org_row.id, 'org_name': org_row.name, 'role': 'admin'}

    def add_membership(self, org_id, accountid, role):
        """Add or update a membership for the given account in the org."""
        # Upsert semantics: try update, if no row then insert.
        with self.engine.connect() as conn:
            existing = conn.execute(
                self.memberships_table.select().where(
                    (self.memberships_table.c.org_id == org_id)
                    & (self.memberships_table.c.accountid == accountid)
                )
            ).first()
            if existing is None:
                statement = self.memberships_table.insert().values(
                    org_id=org_id,
                    accountid=accountid,
                    role=role,
                )
            else:
                statement = self.memberships_table.update().where(
                    self.memberships_table.c.id == existing.id
                ).values(role=role)
            self.logger.debug('QUERY: %s', str(statement))
            conn.execute(statement)

    def remove_membership(self, org_id, accountid):
        """Remove membership for the given account from the org."""
        statement = self.memberships_table.delete().where(
            (self.memberships_table.c.org_id == org_id)
            & (self.memberships_table.c.accountid == accountid)
        )
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            conn.execute(statement)

    def list_items(self, org_id):
        """Return all items belonging to the given organization."""
        statement = self.items_table.select().where(self.items_table.c.org_id == org_id)
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            rows = conn.execute(statement).fetchall()
        return [dict(row) for row in rows]

    def create_item(self, org_id, owner_accountid, name, description=None):
        """Create an item for the given organization."""
        statement = (
            self.items_table.insert()
            .values(
                org_id=org_id,
                owner_accountid=owner_accountid,
                name=name,
                description=description,
            )
            .returning(
                self.items_table.c.id,
                self.items_table.c.org_id,
                self.items_table.c.owner_accountid,
                self.items_table.c.name,
                self.items_table.c.description,
                self.items_table.c.created_at,
            )
        )
        self.logger.debug('QUERY: %s', str(statement))
        with self.engine.connect() as conn:
            row = conn.execute(statement).first()
        return dict(row)

    def get_default_org_for_account(self, accountid):
        """Return an org_id to use as default for the given account, or None."""
        memberships = self.get_memberships_for_account(accountid)
        if not memberships:
            return None
        # Prefer an org where the user is admin, otherwise first membership.
        for membership in memberships:
            if membership['role'] == 'admin':
                return membership['org_id']
        return memberships[0]['org_id']

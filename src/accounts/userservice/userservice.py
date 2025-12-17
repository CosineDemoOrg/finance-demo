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
Userservice manages user account creation, user login, and related tasks
"""

import atexit
from datetime import datetime, timedelta
import logging
import os
import sys
import re

import bcrypt
import jwt
from flask import Flask, jsonify, request
import bleach
from sqlalchemy.exc import OperationalError, SQLAlchemyError

from opentelemetry import trace
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.propagate import set_global_textmap
from opentelemetry.exporter.cloud_trace import CloudTraceSpanExporter
from opentelemetry.propagators.cloud_trace_propagator import CloudTraceFormatPropagator
from opentelemetry.instrumentation.flask import FlaskInstrumentor

from db import UserDb

def create_app():
    """Flask application factory to create instances
    of the Userservice Flask App
    """
    app = Flask(__name__)

    # Disabling unused-variable for lines with route decorated functions
    # as pylint thinks they are unused
    # pylint: disable=unused-variable

    @app.route('/version', methods=['GET'])
    def version():
        """
        Service version endpoint
        """
        return app.config['VERSION'], 200

    @app.route('/ready', methods=['GET'])
    def readiness():
        """
        Readiness probe
        """
        return 'ok', 200

    @app.route('/users', methods=['POST'])
    def create_user():
        """Create a user record.

        Fails if that username already exists.

        Generates a unique accountid.

        request fields:
        - username
        - password
        - password-repeat
        - firstname
        - lastname
        - birthday
        - timezone
        - address
        - state
        - zip
        - ssn
        """
        try:
            app.logger.debug('Sanitizing input.')
            req = {k: bleach.clean(v) for k, v in request.form.items()}
            __validate_new_user(req)
            # Check if user already exists
            if users_db.get_user(req['username']) is not None:
                raise NameError('user {} already exists'.format(req['username']))

            # Create password hash with salt
            app.logger.debug("Creating password hash.")
            password = req['password']
            salt = bcrypt.gensalt()
            passhash = bcrypt.hashpw(password.encode('utf-8'), salt)

            accountid = users_db.generate_accountid()

            # Create user data to be added to the database
            user_data = {
                'accountid': accountid,
                'username': req['username'],
                'passhash': passhash,
                'firstname': req['firstname'],
                'lastname': req['lastname'],
                'birthday': req['birthday'],
                'timezone': req['timezone'],
                'address': req['address'],
                'state': req['state'],
                'zip': req['zip'],
                'ssn': req['ssn'],
            }
            # Add user_data to database
            app.logger.debug("Adding user to the database")
            users_db.add_user(user_data)
            app.logger.info("Successfully created user.")

        except UserWarning as warn:
            app.logger.error("Error creating new user: %s", str(warn))
            return str(warn), 400
        except NameError as err:
            app.logger.error("Error creating new user: %s", str(err))
            return str(err), 409
        except SQLAlchemyError as err:
            app.logger.error("Error creating new user: %s", str(err))
            return 'failed to create user', 500

        return jsonify({}), 201

    def __validate_new_user(req):
        app.logger.debug('validating create user request: %s', str(req))
        # Check if required fields are filled
        fields = (
            'username',
            'password',
            'password-repeat',
            'firstname',
            'lastname',
            'birthday',
            'timezone',
            'address',
            'state',
            'zip',
            'ssn',
        )
        if any(f not in req for f in fields):
            raise UserWarning('missing required field(s)')
        if any(not bool(req[f] or req[f].strip()) for f in fields):
            raise UserWarning('missing value for input field(s)')

        # Verify username contains only 2-15 alphanumeric or underscore characters
        if not re.match(r"\A[a-zA-Z0-9_]{2,15}\Z", req['username']):
            raise UserWarning('username must contain 2-15 alphanumeric characters or underscores')
        # Check if passwords match
        if not req['password'] == req['password-repeat']:
            raise UserWarning('passwords do not match')

    @app.route('/login', methods=['GET'])
    def login():
        """Login a user and return a JWT token

        Fails if username doesn't exist or password doesn't match hash

        token expiry time determined by environment variable

        request fields:
        - username
        - password
        """
        app.logger.debug('Sanitizing login input.')
        username = bleach.clean(request.args.get('username'))
        password = bleach.clean(request.args.get('password'))

        # Get user data
        try:
            app.logger.debug('Getting the user data.')
            user = users_db.get_user(username)
            if user is None:
                raise LookupError('user {} does not exist'.format(username))

            # Validate the password
            app.logger.debug('Validating the password.')
            if not bcrypt.checkpw(password.encode('utf-8'), user['passhash']):
                raise PermissionError('invalid login')

            full_name = '{} {}'.format(user['firstname'], user['lastname'])
            exp_time = datetime.utcnow() + timedelta(seconds=app.config['EXPIRY_SECONDS'])

            # Determine default active organization for this user.
            active_org_id = users_db.get_default_org_for_account(user['accountid'])

            payload = {
                'user': username,
                'acct': user['accountid'],
                'name': full_name,
                'active_org_id': active_org_id,
                'iat': datetime.utcnow(),
                'exp': exp_time,
            }
            app.logger.debug('Creating jwt token.')
            token = jwt.encode(payload, app.config['PRIVATE_KEY'], algorithm='RS256')
            app.logger.info('Login Successful.')
            return jsonify({'token': token}), 200

        except LookupError as err:
            app.logger.error('Error logging in: %s', str(err))
            return str(err), 404
        except PermissionError as err:
            app.logger.error('Error logging in: %s', str(err))
            return str(err), 401
        except SQLAlchemyError as err:
            app.logger.error('Error logging in: %s', str(err))
            return 'failed to retrieve user information', 500

    def _decode_token_from_header():
        """Decode and verify JWT from Authorization header."""
        auth_header = request.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            raise PermissionError('missing bearer token')
        token = auth_header.split(' ', 1)[1]
        try:
            claims = jwt.decode(
                jwt=token,
                key=app.config['PUBLIC_KEY'],
                algorithms=['RS256'],
            )
        except jwt.exceptions.InvalidTokenError as err:
            app.logger.error('Error validating token: %s', str(err))
            raise PermissionError('invalid token') from err
        return claims

    def _require_membership(org_id, require_admin=False):
        """Ensure the current user is a member of the org (and admin if required)."""
        claims = _decode_token_from_header()
        accountid = claims.get('acct')
        if accountid is None:
            raise PermissionError('missing account')
        membership = users_db.get_membership(org_id, accountid)
        if membership is None:
            raise PermissionError('forbidden')
        if require_admin and membership.get('role') != 'admin':
            raise PermissionError('forbidden')
        # Enforce that the active_org_id in the token matches the requested org.
        active_org_id = claims.get('active_org_id')
        if active_org_id is not None and int(active_org_id) != int(org_id):
            raise PermissionError('active org mismatch')
        return claims, membership

    @app.route('/orgs', methods=['GET'])
    def list_orgs():
        """List organizations for the authenticated user."""
        try:
            claims = _decode_token_from_header()
            memberships = users_db.get_memberships_for_account(claims['acct'])
            return jsonify({'organizations': memberships}), 200
        except PermissionError as err:
            return str(err), 401
        except SQLAlchemyError as err:
            app.logger.error('Error listing orgs: %s', str(err))
            return 'failed to list organizations', 500

    @app.route('/orgs', methods=['POST'])
    def create_org():
        """Create an organization and assign caller as admin."""
        try:
            claims = _decode_token_from_header()
            name = request.json.get('name')
            if not name or not name.strip():
                raise UserWarning('missing organization name')
            org = users_db.create_organization(name.strip(), claims['acct'])
            return jsonify(org), 201
        except UserWarning as warn:
            return str(warn), 400
        except PermissionError as err:
            return str(err), 401
        except SQLAlchemyError as err:
            app.logger.error('Error creating org: %s', str(err))
            return 'failed to create organization', 500

    @app.route('/orgs/<int:org_id>/memberships', methods=['POST'])
    def add_membership():
        """Invite/add a member to an organization (admin only)."""
        try:
            claims, _ = _require_membership(org_id, require_admin=True)
            body = request.json or {}
            accountid = body.get('accountid')
            role = body.get('role', 'member')
            if not accountid:
                raise UserWarning('missing accountid')
            users_db.add_membership(org_id, accountid, role)
            return jsonify({}), 204
        except UserWarning as warn:
            return str(warn), 400
        except PermissionError as err:
            return str(err), 403
        except SQLAlchemyError as err:
            app.logger.error('Error adding membership: %s', str(err))
            return 'failed to add membership', 500

    @app.route('/orgs/<int:org_id>/memberships/<accountid>', methods=['DELETE'])
    def remove_membership(org_id, accountid):
        """Remove a member from an organization (admin only)."""
        try:
            _require_membership(org_id, require_admin=True)
            users_db.remove_membership(org_id, accountid)
            return jsonify({}), 204
        except PermissionError as err:
            return str(err), 403
        except SQLAlchemyError as err:
            app.logger.error('Error removing membership: %s', str(err))
            return 'failed to remove membership', 500

    @app.route('/orgs/<int:org_id>/items', methods=['GET'])
    def list_items(org_id):
        """List items scoped to the current organization."""
        try:
            _require_membership(org_id, require_admin=False)
            items = users_db.list_items(org_id)
            return jsonify({'items': items}), 200
        except PermissionError as err:
            return str(err), 403
        except SQLAlchemyError as err:
            app.logger.error('Error listing items: %s', str(err))
            return 'failed to list items', 500

    @app.route('/orgs/<int:org_id>/items', methods=['POST'])
    def create_item(org_id):
        """Create an item scoped to the current organization."""
        try:
            claims, _ = _require_membership(org_id, require_admin=False)
            body = request.json or {}
            name = body.get('name')
            description = body.get('description')
            if not name or not name.strip():
                raise UserWarning('missing item name')
            item = users_db.create_item(
                org_id=org_id,
                owner_accountid=claims['acct'],
                name=name.strip(),
                description=description,
            )
            return jsonify(item), 201
        except UserWarning as warn:
            return str(warn), 400
        except PermissionError as err:
            return str(err), 403
        except SQLAlchemyError as err:
            app.logger.error('Error creating item: %s', str(err))
            return 'failed to create item', 500

    @app.route('/active-org', methods=['POST'])
    def switch_active_org():
        """Switch the active organization for the current user.

        Expects JSON body: {'org_id': <int>}
        Returns a new JWT token with updated active_org_id.
        """
        try:
            claims = _decode_token_from_header()
            body = request.json or {}
            org_id = body.get('org_id')
            if org_id is None:
                raise UserWarning('missing org_id')
            # Ensure the user is a member of the org.
            membership = users_db.get_membership(int(org_id), claims['acct'])
            if membership is None:
                raise PermissionError('forbidden')
            exp_time = datetime.utcnow() + timedelta(seconds=app.config['EXPIRY_SECONDS'])
            new_claims = {
                'user': claims['user'],
                'acct': claims['acct'],
                'name': claims['name'],
                'active_org_id': int(org_id),
                'iat': datetime.utcnow(),
                'exp': exp_time,
            }
            token = jwt.encode(new_claims, app.config['PRIVATE_KEY'], algorithm='RS256')
            return jsonify({'token': token}), 200
        except UserWarning as warn:
            return str(warn), 400
        except PermissionError as err:
            return str(err), 403
        except SQLAlchemyError as err:
            app.logger.error('Error switching active org: %s', str(err))
            return 'failed to switch organization', 500

    @atexit.register
    def _shutdown():
        """Executed when web app is terminated."""
        app.logger.info("Stopping userservice.")

    # Set up logger
    app.logger.handlers = logging.getLogger('gunicorn.error').handlers
    app.logger.setLevel(logging.getLogger('gunicorn.error').level)
    app.logger.info('Starting userservice.')

    # Set up tracing and export spans to Cloud Trace.
    if os.environ['ENABLE_TRACING'] == "true":
        app.logger.info("✅ Tracing enabled.")
        # Set up tracing and export spans to Cloud Trace
        trace.set_tracer_provider(TracerProvider())
        cloud_trace_exporter = CloudTraceSpanExporter()
        trace.get_tracer_provider().add_span_processor(
            BatchSpanProcessor(cloud_trace_exporter)
        )
        set_global_textmap(CloudTraceFormatPropagator())
        FlaskInstrumentor().instrument_app(app)
    else:
        app.logger.info("🚫 Tracing disabled.")

    app.config['VERSION'] = os.environ.get('VERSION')
    app.config['EXPIRY_SECONDS'] = int(os.environ.get('TOKEN_EXPIRY_SECONDS'))
    app.config['PRIVATE_KEY'] = open(os.environ.get('PRIV_KEY_PATH'), 'r').read()
    app.config['PUBLIC_KEY'] = open(os.environ.get('PUB_KEY_PATH'), 'r').read()

    # Configure database connection
    try:
        users_db = UserDb(os.environ.get("ACCOUNTS_DB_URI"), app.logger)
    except OperationalError:
        app.logger.critical("users_db database connection failed")
        sys.exit(1)
    return app


if __name__ == "__main__":
    # Create an instance of flask server when called directly
    USERSERVICE = create_app()
    USERSERVICE.run()

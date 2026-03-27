# User Service

The user service manages user accounts and authentication. 
It creates and signs JWTs that are used by other services to authenticate users.

Implemented in Python with Flask.

### Endpoints

| Endpoint            | Type  | Auth? | Description                                                      |
| ------------------- | ----- | ----- | ---------------------------------------------------------------- |
| `/login`            | GET   |       |  Returns a JWT if authentication is successful.                  |
| `/ready`            | GET   |       |  Readiness probe endpoint.                                       |
| `/users`            | POST  |       |  Validates and creates a new user record.                        |
| `/version`          | GET   |       |  Returns the contents of `$VERSION`                              |

### Environment Variables

- `VERSION`
  - a version string for the service
- `PORT`
  - the port for the webserver
- `PRIV_KEY_PATH`
  - the path to the private key for JWT signing, mounted as a secret
- `TOKEN_EXPIRY_SECONDS`
  - how long JWTs are valid before forcing user logout
- `LOG_LEVEL`
  - the service-specific [logging level](https://docs.python.org/3/library/logging.html#levels) (default: INFO)

- ConfigMap `environment-config`:
  - `LOCAL_ROUTING_NUM`
    - the routing number for our bank
  - `PUB_KEY_PATH`
    - the path to the JWT signer's public key, mounted as a secret

- ConfigMap `accounts-db-config`:
  - `ACCOUNTS_DB_URI`
    - the complete URI for the `accounts-db` database

#### Notifications provider configuration

User-facing email notifications (welcome, password reset, etc.) are sent
through a simple notifications service with a pluggable provider. The
provider is selected at runtime via environment variables.

- `NOTIFICATIONS_PROVIDER`
  - which provider implementation to use
  - supported values:
    - `console` (default): log notification events only
    - `smtp`: send real emails via SMTP

When `NOTIFICATIONS_PROVIDER=smtp`, the following variables are used:

- `SMTP_HOST`
  - hostname of the SMTP server (required)
- `SMTP_PORT`
  - port of the SMTP server (default: `587`)
- `SMTP_USERNAME`
  - SMTP username (optional; if not set, authentication is skipped)
- `SMTP_PASSWORD`
  - SMTP password (optional; only used when `SMTP_USERNAME` is set)
- `SMTP_USE_TLS`
  - whether to use STARTTLS for SMTP connections (`true`/`false`, default: `true`)
- `SMTP_FROM_ADDRESS`
  - email address used for the `From:` header (required)

> To switch providers, update `NOTIFICATIONS_PROVIDER` on the
> `userservice` Deployment (for example from `console` to `smtp`) and
> redeploy. No frontend changes are required.

### Kubernetes Resources

- [deployments/userservice](/kubernetes-manifests/userservice.yaml)
- [service/userservice](/kubernetes-manifests/userservice.yaml)

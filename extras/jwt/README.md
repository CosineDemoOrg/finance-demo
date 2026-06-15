# JWT Key Pair Secret

Bank of Anthos uses [Json Web Tokens (JWTs)](https://jwt.io/introduction/) to handle user authentication.
JWTs use asymmetric key pairs to sign and verify tokens.
In this case, `userservice` creates and signs tokens with a RSA private key when a user logs in,
and the other services use the corresponding public key to validate the user.

This directory contains a **template** for the [Secret](https://kubernetes.io/docs/concepts/configuration/secret/)
containing an RSA key pair. **The committed keypair has been removed for security (see C-1).**

## WARNING: Do NOT commit real keys to version control

The previous `jwt-secret.yaml` contained a hardcoded RSA private key. This is a critical security
vulnerability because anyone who cloned the repository could extract the private key and forge valid JWT
tokens. The real keys have been removed and replaced with a template.

## Generating Fresh Keys (Recommended)

Use the provided Python script to generate a fresh RSA keypair and write it to the Kubernetes Secret:

```
python3 scripts/generate_jwt_keys.py
```

This script uses the `cryptography` library to generate a 2048-bit RSA keypair and writes the base64-encoded
PEM keys to `extras/jwt/jwt-secret.yaml` in the correct Kubernetes Secret format. It is idempotent – each
run produces a new keypair.

## Creating Secret Manually (Alternative)

```
  openssl genrsa -out jwtRS256.key 4096
  openssl rsa -in jwtRS256.key -outform PEM -pubout -out jwtRS256.key.pub
  kubectl create secret generic jwt-key --from-file=./jwtRS256.key --from-file=./jwtRS256.key.pub
```

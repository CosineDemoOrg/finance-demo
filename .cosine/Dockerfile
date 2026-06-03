FROM ubuntu:latest
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv python3-pip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
RUN python3 -m venv /opt/frontend-venv \
    && /opt/frontend-venv/bin/python -m pip install --upgrade pip \
    && /opt/frontend-venv/bin/python -m pip install --no-cache-dir -r /workspace/src/frontend/requirements.txt

RUN mkdir -p /tmp/.ssh \
    && printf '%s' 'LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUlJQ0lqQU5CZ2txaGtpRzl3MEJBUUVGQUFPQ0FnOEFNSUlDQ2dLQ0FnRUE0R3ZaTlNrajVpaVdiMkRaTTJmQwp4NWJHR1lnd2FLNFNNc3lJOEs4b0ZRZXBMMTNZUk9YS3c1UytsRUxIOUFldFRkY1RBZGtiQ3p5YXBTZGszUHF3CnhoQ2FmNGtudktlUmk3NmNzRzZtYzBrcngxR0l0WTY1WCtOcTBxYVJ2TGRPT2JGUUdtSUh4NzV6a1o2NTQrZnQKdm52Q1k1T1BqVjQ0TGt0dTF3bHJCbXIyMjVRU0ZiSWE4QTk2QlNwSnpLbTBhaEsrNnI4VnZ2T3ExQkhHWklJSwpLR1E3L3pMdXhQT24rYzN3Q0YwVlFZMjBZNFhvbytEVnQvKzBNSThPWnZkQXVhYjJVWHViK1Rpd0FnRzc2OHluCkpQaDdla2xMdGR0QTFCR2dsWG1vRVI0eHZoaFhRc09SdFRpNjBGWlBYN0MzVjFybFVlK3JVOUJZNkNscldsZUoKdGdCZjFYUi90Qm1MZGZCNFlWbkt0ekI3dkxDbE9LdWx0YWR1U1ZuZnY4QzB1ejF6UEJaSXdYbnNWTzdYeVNWUApBSkJUOFBWR1Iza2JnOW5FRGsvclZsS1lNSW5EU084U0hOeUtPK2s2cFRobWFtb3FNQkpCQ3BjQytHMVVQVXFZCmV1Szh3NjZaU3BLV2ZQY3RtWWViUHNraUpiS2VPckhWVTJEajd6emRpbUwyYmVocVN3U2kzekFrbFdmNEZTQXoKaC80enBENFdxM0lDd2NYbFBxYTU5T0IwcXFBQ3g1QW1FRmx2UXVVSTBxNndaNnZybEFZSytNZG00RHZGQVVsWApzNW1mbDZPRnZ0YkZTZHF1ckU2SXROVnlSVlFBbHpYV21oQzhHcldCSWh0MU9KTFdES081bHZabUpRL2xrN2JzClVyNCsyK05ObnNzTXBuTUI2QytJejZzQ0F3RUFBUT09Ci0tLS0tRU5EIFBVQkxJQyBLRVktLS0tLQo=' | base64 -d > /tmp/.ssh/publickey \
    && chmod a+r /tmp/.ssh/publickey

ENV PATH=/opt/frontend-venv/bin:$PATH \
    VERSION=v0.6.7 \
    PORT=8080 \
    ENABLE_TRACING=false \
    SCHEME=http \
    LOG_LEVEL=info \
    LOCAL_ROUTING_NUM=883745000 \
    PUB_KEY_PATH=/tmp/.ssh/publickey \
    TRANSACTIONS_API_ADDR=ledgerwriter:8080 \
    BALANCES_API_ADDR=balancereader:8080 \
    HISTORY_API_ADDR=transactionhistory:8080 \
    CONTACTS_API_ADDR=contacts:8080 \
    USERSERVICE_API_ADDR=userservice:8080 \
    DEFAULT_USERNAME=testuser \
    DEFAULT_PASSWORD=bankofanthos \
    ENV_PLATFORM=local

WORKDIR /workspace/src/frontend
EXPOSE 8080
CMD ["sh", "-c", "gunicorn -b :$PORT --threads 4 --log-config logging.conf --log-level=$LOG_LEVEL 'frontend:create_app()'"]

ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TVCAST_DATA_DIR=/app/data

WORKDIR /app

COPY requirements.txt ./
COPY wheels ./wheels
RUN python -m pip install \
    --no-cache-dir \
    --no-index \
    --find-links=/app/wheels \
    -r requirements.txt

RUN groupadd --system --gid 10001 tvcast \
    && useradd --system --uid 10001 --gid tvcast --home-dir /app tvcast

COPY --chown=tvcast:tvcast . .
RUN mkdir -p /app/data/apk && chown -R tvcast:tvcast /app/data

USER tvcast

EXPOSE 18000/tcp 18001/udp

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:18000/api/health', timeout=3).read()"

# --forwarded-allow-ips used to be "*", so any client could set X-Forwarded-For itself and be
# treated as the TV's LAN peer. Now only the private docker networks the reverse proxy runs on are
# trusted; override with TVCAST_TRUSTED_PROXIES when the proxy lives elsewhere.
CMD ["sh", "-c", "exec python -m uvicorn main:app --host 0.0.0.0 --port 18000 --workers 1 --proxy-headers --forwarded-allow-ips \"${TVCAST_TRUSTED_PROXIES:-127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16}\""]

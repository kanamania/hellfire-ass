FROM python:3.14-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY logging.json .
RUN useradd --create-home --uid 10001 hellfire && mkdir /app/state /app/web-state && chown hellfire:hellfire /app/state /app/web-state
USER hellfire
# Rate limits key on the client address the reverse proxy forwards (FORWARDED_ALLOW_IPS in compose.yaml).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--log-config", "logging.json"]

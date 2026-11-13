FROM python:3.14-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY logging.json .
RUN useradd --create-home --uid 10001 hellfire && mkdir /app/state && chown hellfire:hellfire /app/state
USER hellfire
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--log-config", "logging.json"]

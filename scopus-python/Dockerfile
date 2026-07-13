# Scopus wrapper service — the FastAPI + pybliometrics app the Java core calls for author-works
# and citations-by-eid. Runs as an internal container on-prem (no more laptop-at-a-public-IP).
FROM python:3.12-slim

# Non-root; HOME under /app so pybliometrics writes its config/cache somewhere writable.
RUN useradd --create-home --uid 10001 appuser
ENV HOME=/app \
    PORT=65008 \
    PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# The source file has dashes (not importable as a module), so land it as app.py for `uvicorn app:app`.
COPY pybib-wrapper-with-logs.py ./app.py
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh && chown -R appuser:appuser /app

USER appuser
EXPOSE 65008

# Health is Scopus-free — green as soon as the app is up, even without a real key.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD python -c "import os,urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','65008')+'/v1/health',timeout=2).status==200 else 1)"

ENTRYPOINT ["./entrypoint.sh"]

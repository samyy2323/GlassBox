FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    FASTEMBED_CACHE_PATH=/models
WORKDIR /srv

COPY requirements.txt .
# Generous timeouts and a build cache: wheels are large and some networks are slow.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --default-timeout=180 --retries 10 -r requirements.txt

# Bake the default models into the image so containers start offline and fast.
RUN python -c "from fastembed import TextEmbedding; from fastembed.rerank.cross_encoder import TextCrossEncoder; \
TextEmbedding(model_name='BAAI/bge-small-en-v1.5'); TextCrossEncoder(model_name='Xenova/ms-marco-MiniLM-L-6-v2')" \
    && useradd --system --uid 10001 --no-create-home sidecar && chmod -R a+rX /models

# The NLI weights come from a standalone module, so changing application code does not download them again.
COPY app/nli_model.py /tmp/nli_model.py
RUN python /tmp/nli_model.py && chmod -R a+rX /models && rm /tmp/nli_model.py

COPY app app
USER 10001
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health', timeout=4).status == 200 else 1)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

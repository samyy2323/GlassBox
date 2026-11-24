# One image with everything, for a host that runs a single container (Hugging Face Spaces, Fly, Railway...).
# Postgres + pgvector, the API, the background worker, the embedding sidecar, nginx and the built web app, under supervisord, as an unprivileged user.
# Build context: the repository root.  docker build -f deploy/space/Dockerfile -t glassbox-space .
FROM node:20-alpine AS web
WORKDIR /app
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
COPY apps/api/pom.xml apps/api/pom.xml
COPY db db
RUN --mount=type=cache,target=/root/.m2 mvn -f apps/api/pom.xml -q -B dependency:go-offline
COPY apps/api/src apps/api/src
RUN --mount=type=cache,target=/root/.m2 mvn -f apps/api/pom.xml -q -B -DskipTests package

FROM pgvector/pgvector:pg16
ENV DEBIAN_FRONTEND=noninteractive PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 FASTEMBED_CACHE_PATH=/models \
    JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/lib/postgresql/16/bin:/opt/venv/bin:$PATH
RUN apt-get update && apt-get install -y --no-install-recommends nginx supervisor python3 python3-venv curl ca-certificates openssl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk

# The sidecar: dependencies, then the models baked in (the host may have no internet access at run time and the disk is thrown away on restart).
COPY apps/ml-sidecar/requirements.txt /tmp/requirements.txt
RUN python3 -m venv /opt/venv && pip install --no-cache-dir --default-timeout=180 --retries 10 -r /tmp/requirements.txt
RUN python -c "from fastembed import TextEmbedding; from fastembed.rerank.cross_encoder import TextCrossEncoder; \
TextEmbedding(model_name='BAAI/bge-small-en-v1.5'); TextEmbedding(model_name='BAAI/bge-base-en-v1.5'); TextEmbedding(model_name='sentence-transformers/all-MiniLM-L6-v2'); \
TextCrossEncoder(model_name='Xenova/ms-marco-MiniLM-L-6-v2')"
COPY apps/ml-sidecar/app/nli_model.py /tmp/nli_model.py
RUN python /tmp/nli_model.py && rm /tmp/nli_model.py
COPY apps/ml-sidecar/app /srv/app

COPY --from=api /src/apps/api/target/glassbox-api-*.jar /app/app.jar
COPY --from=web /app/dist /usr/share/nginx/html
COPY db/migrations /app/migrations
COPY deploy/db/init/01-roles.sh /app/01-roles.sh
COPY deploy/space/nginx.conf /etc/nginx/nginx.conf
COPY deploy/space/supervisord.conf /etc/supervisord.conf
COPY deploy/space/entrypoint.sh /app/entrypoint.sh
COPY deploy/space/seed /app/seed

# Spaces run the container as uid 1000.
RUN useradd -m -u 1000 user && chmod +x /app/entrypoint.sh && chown -R 1000 /app /models /srv /usr/share/nginx/html
USER 1000
ENV HOME=/home/user
WORKDIR /home/user
EXPOSE 7860
HEALTHCHECK --interval=20s --timeout=5s --start-period=120s --retries=6 CMD curl -fsS http://127.0.0.1:7860/healthz || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]

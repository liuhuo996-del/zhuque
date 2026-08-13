FROM node:22-alpine AS frontend
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY index.html postcss.config.js tailwind.config.ts tsconfig.json vite.config.ts ./
COPY src ./src
RUN npm run build


FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    GATEFORGE_DATABASE_PATH=/data/gateforge.db \
    GATEFORGE_STATIC_DIR=/app/static

WORKDIR /app

RUN groupadd --gid 10001 gateforge \
    && useradd --uid 10001 --gid gateforge --no-create-home --home-dir /app --shell /usr/sbin/nologin gateforge

COPY backend/pyproject.toml /tmp/gateforge/pyproject.toml
RUN pip install \
      'cryptography>=43,<47' \
      'fastapi>=0.115,<1' \
      'httpx>=0.27,<1' \
      'jsonschema>=4.23,<5' \
      'pydantic>=2.9,<3' \
      'pydantic-settings>=2.5,<3' \
      'PyYAML>=6.0,<7' \
      'uvicorn[standard]>=0.30,<1'
COPY backend/src /tmp/gateforge/src
RUN pip install --no-deps /tmp/gateforge \
    && rm -rf /tmp/gateforge

COPY --from=frontend /build/dist /app/static

RUN mkdir -p /data && chown gateforge:gateforge /data

USER 10001:10001
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8081/api/health', timeout=2).read()"]

CMD ["uvicorn", "gateforge.main:app", "--host", "0.0.0.0", "--port", "8081", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips=*"]

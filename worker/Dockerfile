# One image, two roles: WORKER_ROLE=ingest|agent. Build context is the repository root.
#   docker build -f worker/Dockerfile .
FROM python:3.12-slim AS build
RUN pip install --no-cache-dir uv==0.12.1
WORKDIR /src
COPY worker/pyproject.toml worker/uv.lock ./
# Dependencies are installed from the hash-pinned export only.
RUN uv export --frozen --no-dev --no-emit-project --format requirements-txt -o requirements.txt \
 && python -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir --require-hashes --no-deps -r requirements.txt
COPY worker/src ./src
RUN /opt/venv/bin/pip install --no-cache-dir --no-deps --no-build-isolation . 2>/dev/null \
 || (pip install --no-cache-dir hatchling==1.27.0 && /opt/venv/bin/pip install --no-cache-dir --no-deps . )

FROM python:3.12-slim
# LibreOffice converts DOCX to the PDF the viewer shows (AI 3.1); metric-compatible fonts keep the
# layout of Word documents close to the original (Carlito for Calibri, Liberation for Arial and
# Times New Roman, Caladea for Cambria).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libreoffice-writer fonts-liberation fonts-crosextra-carlito fonts-crosextra-caladea fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin worker
COPY --from=build /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER 10001
EXPOSE 8080
ENTRYPOINT ["python", "-m", "tender_worker"]

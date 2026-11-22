# IAPC – RAG platform

Team V²: Lixandru Valentina Mariana, Palatka Vanessa.

Spring Boot 4 + Spring AI microservices with PostgreSQL/pgvector, Eureka and Spring Cloud Gateway.
Epic 1 (RAG) is due on **December 7**.

## Services

| Module | What it does | Local port | Tasks (owner) |
|---|---|---|---|
| `rag-common` | Shared library: chunk metadata contract, PDF extraction, cleaning, chunking | – | – |
| `eureka-server` | Service registry | 8761 | – |
| `api-gateway` | Single entry point, routes by service name | 8080 | – |
| `ingest-static` | Story 1: static PDF ingestion | 8081 | T1, T2 (Vanessa) |
| `ingest-dynamic` | Story 2: Internet ingestion, versioning | 8082 | T1, T2 (Valentina) |
| `retrieval-pre` | Story 3: metadata filters, query normalization | 8083 | T1, T2 (Vanessa) |
| `retrieval-post` | Story 4: reranking, neighbor stitching | 8084 | T1, T2 (Valentina) |

`ingest-dynamic`, `retrieval-pre` and `retrieval-post` are skeletons for now (health check and Eureka registration only).

In Docker every service listens on 8080 inside its container and is reached through the gateway:
`http://localhost:8080/<module>/...`, for example `POST http://localhost:8080/ingest-static/ingest/static/run`.

## Getting started

Prerequisites: JDK 21 and Docker.

```bash
cp .env.example .env          # then fill in POSTGRES_PASSWORD and LLM_API_KEY
docker compose up --build -d  # everything in Docker
docker compose ps             # all services should become "healthy"
```

If Docker reports that port 5432 is not available (a local PostgreSQL, or a port reserved by Windows),
set `POSTGRES_PORT=5433` in `.env`; the services still use 5432 inside the Docker network.

To work on one service from the IDE, start only the infrastructure in Docker and run the service's
`*Application` class with the **repository root as working directory** (so `.env` and `./resources` are found):

```bash
docker compose up -d pgvector eureka-server
```

## Build and tests

Always build from the repository root (the modules depend on `rag-common`):

```bash
./mvnw verify                          # all modules (Windows: mvnw.cmd verify)
./mvnw -pl ingest-static -am verify    # one module and what it depends on
```

Unit tests need no infrastructure. Tests that start the full context against PostgreSQL and the LLM API are
skipped unless `RUN_INTEGRATION_TESTS=true` is set.

## Configuration

All settings come from environment variables with the same names locally and in Docker; `.env.example` lists
and documents every one of them. Never commit `.env`. Add any new setting to `.env.example` in the same PR.

## Chunk metadata contract

Every chunk in `vector_store.metadata` carries these keys (constants in `rag-common`, class `ChunkMetadata`).
Add a key there before using it in any service.

| Key | Meaning |
|---|---|
| `doc_id` | Stable document id (file name for static PDFs) |
| `version`, `is_latest` | Document version (from 1); only the newest version has `is_latest=true` |
| `chunk_index`, `total_chunks` | Position of the chunk in its document version (0-based) and chunk count |
| `page`, `page_end` | First and last page of the chunk (1-based) |
| `source`, `source_type` | Name shown in citations; `static` or `dynamic` |
| `source_url`, `fetched_at`, `valid_from` | Story 2: origin URL, fetch time, start of validity of the version |
| `checksum`, `ingest_fingerprint` | SHA-256 of the content; checksum plus settings, used to skip unchanged files |
| `embedding_model`, `chunk_size`, `chunk_overlap`, `ingested_at` | How and when the chunk was produced |

## Story 1 endpoints (`ingest-static`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/ingest/static/run` | Ingest every PDF in `INGEST_RESOURCES_DIR`; unchanged files are skipped |
| GET | `/ingest/static/sample?n=20` | Random chunks with quality flags, for the manual check |
| GET | `/ingest/static/status` | Service status |

## How we work

1. One branch per task, e.g. `s2-t1-internet-ingestion`. No direct pushes to `main`.
2. Open a pull request that meets the definition of done of the task.
3. The other team member reviews; merge only when `./mvnw verify` passes.

## Known issues

- `resources/paypal_consumer_fees_2026.pdf` is a scanned PDF with no text layer, so ingestion reports it as
  failed (OCR is not supported).
- `resources/raiffeisen_sme_fees_2024.pdf` is 61 MB, close to GitHub's 100 MB limit. Do not add larger files;
  compress them first.

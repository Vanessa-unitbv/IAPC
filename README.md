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

`retrieval-pre` and `retrieval-post` are skeletons for now (health check and Eureka registration only).

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

Unit tests need no infrastructure. Context-based integration tests are skipped unless `RUN_INTEGRATION_TESTS=true`
is set. `ChunkWriterUpdateIntegrationTest` requires PostgreSQL, stubs vector-store writes, and makes no LLM API calls.
GitHub Actions runs `mvn verify` on every pull request.

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
| `source_url`, `fetched_at`, `title` | Story 2: origin URL, fetch time, page title (when there is one) |
| `valid_from`, `valid_to` | Story 2: when the version became valid, and when a newer version replaced it (absent on the latest) |
| `checksum`, `ingest_fingerprint` | SHA-256 of the content; checksum plus settings, used to skip unchanged files |
| `embedding_model`, `chunk_size`, `chunk_overlap`, `ingested_at` | How and when the chunk was produced |

## Story 1 endpoints (`ingest-static`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/ingest/static/run` | Ingest every PDF in `INGEST_RESOURCES_DIR`; unchanged files are skipped |
| GET | `/ingest/static/sample?n=20` | Random chunks with quality flags, for the manual check |
| GET | `/ingest/static/status` | Service status |

## Story 2 endpoints (`ingest-dynamic`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/ingest/dynamic/run` | Ingest the sources in the JSON body, or the configured `app.ingest.sources` when there is no body |
| GET | `/ingest/dynamic/search?q=...&k=5` | Semantic search over the latest version of every document |
| GET | `/ingest/dynamic/search?q=...&doc_id=...&version=1` | The same search over one specific (older) version |
| GET | `/ingest/dynamic/status` | Service status, allowed domains and number of configured sources |

A source is either a URL (web page, PDF, text or Markdown file) or a GitHub repository's documentation files:

```bash
curl -X POST http://localhost:8080/ingest-dynamic/ingest/dynamic/run -H "Content-Type: application/json" -d '[
  {"url": "https://en.wikipedia.org/wiki/Deposit_insurance"},
  {"url": "https://www.brd.ro/_files/pdf/Formular_pentru_informatii_oferite_deponentilor.pdf"},
  {"repo": "pgvector/pgvector", "ref": "master", "paths": ["README.md"]}
]'
```

The response lists the sources that were ingested for the first time (`ingested`), stored as a new version
(`newVersions`), re-indexed (`reindexed`), skipped as unchanged (`unchanged`), and the ones that failed with the
reason (`failed`); a failed source never stops the rest of the batch. Every chunk stores `source_url`, `fetched_at`,
`valid_from` and, for web pages, `title` (see the metadata contract above). `doc_id` is the source URL; for
repository files it is the file's GitHub page.

What happens to a source:

1. **Fetch** (`SafeHttpFetcher`): HTTPS only, the host must be on `INGEST_ALLOWED_DOMAINS` and must not resolve to
   a loopback, private, link-local or metadata address. Redirects (at most 3) are checked the same way. Requests time
   out after `INGEST_TIMEOUT_SECONDS`; bodies over `INGEST_MAX_SIZE_MB` and content types other than HTML, PDF,
   plain text and Markdown are rejected.
2. **Extract**: web pages lose navigation, headers, footers, sidebars, cookie banners, scripts and forms
   (`HtmlTextExtractor`); Markdown loses badges, images, link targets and HTML (`MarkdownText`); PDFs use the same
   extractor as Story 1.
3. **Split and store**: the same structure-aware splitter as Story 1, then versioning (Task 2):
   - new source: stored as version 1;
   - same content and settings: skipped;
   - **content changed**: stored as version N+1 with `is_latest=true` and `valid_from` = fetch time; the previous
     version stays in the store with `is_latest=false` and `valid_to` = the same time;
   - same content, settings changed (chunking, embedding model, pipeline version): the latest version is re-indexed
     in place, without a new version number.

   Both writes are atomic under a per-document lock, so a query never sees two latest versions or none.

Queries see only latest versions unless they ask for one: the rule is `VersionSelector` in `rag-common`
(`latest()` by default, `version(docId, n)` on request), so retrieval in Story 3 applies the same default.

`ingest-dynamic` writes to the `vector_store` table created by `ingest-static`, so start `ingest-static` first.

## How we work

1. One branch per task, e.g. `s2-t1-internet-ingestion`. No direct pushes to `main`.
2. Open a pull request; the PR template contains the definition of done.
3. The other team member reviews; merge only when CI is green.



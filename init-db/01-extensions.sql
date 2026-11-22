-- Runs once, on first start of an empty data volume.
-- Extensions required by Spring AI's PgVectorStore.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

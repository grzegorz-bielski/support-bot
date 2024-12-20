package supportbot
package clickhouse

import unindent.*

lazy val AllMigrations = Vector(
  Migration(
    name = "create_context_table",
    ddl = i"""
            CREATE TABLE IF NOT EXISTS contexts
            (
              id UUID,                            -- unique identifier of the context
              name String,                        -- name of the context
              description String,                 -- description of the context
              prompt_template String,             -- a stringified JSON of the prompt template
              retrieval_settings String,          -- a stringified JSON of the retrieval settings
              chat_completion_settings String,    -- a stringified JSON of the chat completion settings 
              chat_model String,                  -- name of the chat model
              embeddings_model String,            -- name of the embeddings model
              updated_at DateTime DEFAULT now(),

              -- this essentially doubles the size of the table, but allows for faster filtering by name (there are no row indexes in CH)
              -- we don't expect the number of contexts to be huge, so it should be fine, but perhaps a bloom filter data skipping index would work too
              PROJECTION context_name_projection (SELECT * ORDER BY name)
            )
            ENGINE = ReplacingMergeTree()
            ORDER BY (toUInt128(id))
            SETTINGS
              deduplicate_merge_projection_mode = 'rebuild',
              lightweight_mutation_projection_mode = 'rebuild'
            """,
  ),
  Migration(
    name = "create_documents_table",
    ddl = i"""
            CREATE TABLE IF NOT EXISTS documents
            (
              id UUID,                                    -- unique identifier of the document
              context_id UUID,                            -- unique identifier of the context
              name String,                                -- name of the document, like a file name
              description String,                         -- description of the document
              version Int64,                              -- version of the document
              type String,                                -- type of the document, like 'pdf', 'docx', 'txt', etc. TODO: make it an enum ?
              metadata Map(String, String),               -- any additional metadata of the document
              updated_at DateTime DEFAULT now()
            )
            ENGINE = ReplacingMergeTree()
            ORDER BY (toUInt128(context_id), toUInt128(id)) -- CH's UUIDs are sorted by their second half, so we need to convert them to UInt128 for proper ordering
            """,
  ),
  Migration(
    name = "create_embeddings_table",
    ddl = i"""
            CREATE TABLE IF NOT EXISTS embeddings
            (
              context_id UUID,                                                           -- unique identifier of the context
              document_id UUID,                                                          -- unique identifier of the document
              fragment_index Int64,                                                      -- index of the fragment (like page) in the document, if there is no clear separation of fragments in source document, it will be equal to chunk_index
              chunk_index Int64,                                                         -- index of the chunk in the fragment, if there is no clear separation into fragments in source document, it will be always 0
              value String,                                                              -- value (likely just text) of the chunk
              metadata Map(String, String),                                              -- any additional metadata of the chunk
              embedding Array(Float32),                                                  -- embedding vector of the chunk
              updated_at DateTime DEFAULT now(),
              INDEX ann_idx embedding TYPE vector_similarity('hnsw', 'cosineDistance'),  -- ANN index for fast retrieval of embeddings according to the cosine distance
              INDEX inv_idx value TYPE full_text()                                       -- inverted index for full-text search
            )
            ENGINE = MergeTree()                                           -- not replacing, as we want to keep all embeddings for a given fragment_index
            ORDER BY (toUInt128(context_id), toUInt128(document_id), fragment_index, chunk_index) -- CH's UUIDs are sorted by their second half, so we need to convert them to UInt128 for proper ordering
            """,
  ),
)

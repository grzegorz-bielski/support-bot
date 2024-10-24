package supportbot
package clickhouse

import unindent.*

lazy val AllMigrations = Vector(
    Migration(
        name = "create_context_table",
        ddl = 
            i"""
            CREATE TABLE IF NOT EXISTS contexts
            (
                id UUID,                            -- unique identifier of the context
                name String,                        -- name of the context
                description String,                 -- description of the context
                prompt String,                      -- a stringified JSON of the prompt, TODO: move the new JSON type once it's available?
                chat_model String,                  -- name of the chat model
                embeddings_model String,            -- name of the embeddings model
                updated_at DateTime DEFAULT now()
            )
            ENGINE = ReplacingMergeTree()
            ORDER BY (toUInt128(id))
            """
    ),
    Migration(
        name = "create_documents_table",
        ddl = 
            i"""
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
            """
    ),
    Migration(
        name = "create_embeddings_table",
        ddl = 
            i"""
            CREATE TABLE IF NOT EXISTS embeddings
            (
                document_id UUID,                                          -- unique identifier of the document
                fragment_index Int64,                                      -- index of the fragment (like page) in the document, if there is no clear separation of fragments in source document, it will be equal to chunk_index
                chunk_index Int64,                                         -- index of the chunk in the fragment, if there is no clear separation into fragments in source document, it will be always 0
                value String,                                              -- base64 encoded value (likely just text) of the chunk, TODO: not base64 encode it so it's usable for full text search?
                metadata Map(String, String),                              -- any additional metadata of the chunk
                embedding Array(Float32),                                  -- embedding vector of the chunk
                updated_at DateTime DEFAULT now(),
                INDEX ann_idx embedding TYPE usearch('cosineDistance')     -- ANN index for fast retrieval of embeddings similar according to cosine distance
            )
            ENGINE = MergeTree()                                           -- not replacing, as we want to keep all embeddings for a given fragment_index
            ORDER BY (toUInt128(document_id), fragment_index, chunk_index) -- CH's UUIDs are sorted by their second half, so we need to convert them to UInt128 for proper ordering
            """
    ),
)

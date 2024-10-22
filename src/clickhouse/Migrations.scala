package supportbot
package clickhouse

import unindent.*

lazy val AllMigrations = Vector(
    Migration(
        name = "create_embeddings_table",
        ddl = 
            i"""
            CREATE TABLE IF NOT EXISTS embeddings
            (
                document_name String,         -- name of the document, like a file name
                document_version Int64,       -- version of the document, for now it's 1
                fragment_index Int64,         -- index of the fragment (like page) in the document
                chunk_index Int64,            -- index of the chunk in the fragment
                value String,                 -- base64 encoded value (likely just text) of the chunk
                metadata Map(String, String), -- any additional metadata of the chunk
                embedding Array(Float32),
                INDEX ann_idx embedding TYPE usearch('cosineDistance')
            )
            ENGINE = MergeTree()            -- not replacing, as we want to keep all embeddings for a given fragment_index
            ORDER BY (document_name, document_version, fragment_index)
            """
    )
)

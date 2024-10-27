package supportbot
package rag

import java.util.UUID

trait DocumentRepository[F[_]]:
    def createOrUpdate(info: Document.Info): F[Unit]
    def getAll(contextId: ContextId): F[Vector[Document.Info]]
    def delete(id: DocumentId): F[Unit]

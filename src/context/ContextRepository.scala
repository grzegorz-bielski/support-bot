package supportbot
package context

import java.util.UUID
import java.time.*

trait ContextRepository[F[_]]:
    def createOrUpdate(info: ContextInfo): F[Unit]
    def getAll: F[Vector[ContextInfo]]
    def get(contextId: ContextId): F[Option[ContextInfo]]
    def delete(id: ContextId): F[Unit]

final case class ContextInfo(
    id: ContextId,
    name: String,
    description: String,
    prompt: Prompt,
    chatModel: Model,
    embeddingsModel: Model,
)

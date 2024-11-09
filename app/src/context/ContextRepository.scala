package supportbot
package context

import java.util.UUID
import java.time.*
import cats.effect.*

trait ContextRepository[F[_]]:
    def createOrUpdate(info: ContextInfo): F[Unit]
    def getAll: F[Vector[ContextInfo]]
    def get(contextId: ContextId): F[Option[ContextInfo]]
    def delete(id: ContextId): F[Unit]

final case class ContextInfo(
    id: ContextId,
    name: String,
    description: String,
    promptTemplate: PromptTemplate,
    chatModel: Model,
    embeddingsModel: Model,
)

object ContextInfo:
    def default: IO[ContextInfo] = 
        ContextId.of.map(default)

    def default(id: ContextId): ContextInfo =
        ContextInfo(
            id = id,
            name = "Support bot",
            description = "Your new blank support bot. Configure it to your needs.",
            promptTemplate = PromptTemplate.default,
            chatModel = Model.defaultChatModel,
            embeddingsModel = Model.defaultEmbeddingsModel,
        )

package supportbot
package context

import java.util.UUID
import cats.effect.*

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

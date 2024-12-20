package supportbot

import cats.effect.*
import java.util.UUID
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import cats.syntax.all.*
import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder.*
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import io.scalaland.chimney.dsl.*
import io.scalaland.chimney.*

import supportbot.common.*

import ContextInfo.given

final case class ContextInfo(
  id: ContextId,
  name: String,
  description: String,
  promptTemplate: PromptTemplate,
  retrievalSettings: RetrievalSettings,
  chatCompletionSettings: ChatCompletionSettings,
  chatModel: Model,
  embeddingsModel: Model,
)

object ContextInfo:
  def default: IO[ContextInfo] =
    ContextId.of.map(default(_))

  def default(
    id: ContextId,
    name: String = "Support bot",
    description: String = "Your new blank support bot. Configure it to your needs.",
  ): ContextInfo =
    ContextInfo(
      id = id,
      name = name,
      description = description,
      promptTemplate = PromptTemplate.default,
      retrievalSettings = RetrievalSettings.default,
      chatCompletionSettings = ChatCompletionSettings.default,
      chatModel = Model.defaultChatModel,
      embeddingsModel = Model.defaultEmbeddingsModel,
    )

  given PartialTransformer[String, PromptTemplate]         = stringJsonPartial
  given PartialTransformer[String, RetrievalSettings]      = stringJsonPartial
  given PartialTransformer[String, ChatCompletionSettings] = stringJsonPartial
  given PartialTransformer[String, Model]                  = stringJsonPartial

  private def stringJsonPartial[T: JsonValueCodec]: PartialTransformer[String, T] =
    PartialTransformer.fromFunction(_.unsafeParseToJson[T])

final case class ContextInfoFormDto(
  name: String,
  description: String,
  promptTemplate: String,
  retrievalSettings: String,
  chatCompletionSettings: String,
  chatModel: Model,
  embeddingsModel: Model,
):
  def asContextInfo(id: ContextId): Either[String, ContextInfo] =
    this
      .intoPartial[ContextInfo]
      .withFieldConst(_.id, id)
      .transform
      .asEitherErrorPathMessageStrings
      .leftMap(_.mkString(", "))

object ContextInfoFormDto:
  given QueryParamDecoder[Model] = QueryParamDecoder[String].emap: str =>
    Model.from(str).toRight(ParseFailure(str, "Invalid chat model"))

  given FormDataDecoder[ContextInfoFormDto] = (
    field[String]("name").sanitized,
    field[String]("description").sanitized,
    field[String]("promptTemplate").sanitized,
    field[String]("retrievalSettings").sanitized,
    field[String]("chatCompletionSettings").sanitized,
    field[Model]("chatModel"),
    field[Model]("embeddingsModel"),
  ).mapN(ContextInfoFormDto.apply)


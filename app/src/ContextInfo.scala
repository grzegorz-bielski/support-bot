package supportbot

import cats.effect.*
import java.util.UUID
import com.github.plokhotnyuk.jsoniter_scala.core.*
import cats.syntax.all.*
import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder.*
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import io.scalaland.chimney.dsl.*
import io.scalaland.chimney.*

import ContextInfoFormDto.given

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


final case class ContextInfoFormDto(
  name: String,
  description: String,
  promptTemplate: String,
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
  given PartialTransformer[String, PromptTemplate] =
    PartialTransformer.fromFunction(_.unsafeParseToJson[PromptTemplate])

  given QueryParamDecoder[Model] = QueryParamDecoder[String].emap: str =>
    Model.from(str).toRight(ParseFailure(str, "Invalid chat model"))

  given FormDataDecoder[ContextInfoFormDto] = (
    field[String]("name").sanitized,
    field[String]("description").sanitized,
    field[String]("promptTemplate").sanitized,
    field[Model]("chatModel"),
    field[Model]("embeddingsModel"),
  ).mapN(ContextInfoFormDto.apply)

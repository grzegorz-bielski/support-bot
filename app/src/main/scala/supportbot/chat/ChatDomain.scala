package supportbot
package chat

import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder.*
import java.util.UUID
import cats.kernel.Eq
import cats.effect.*
import cats.syntax.all.*

opaque type QueryId <: UUID = UUID
object QueryId:
  inline def apply(uuid: UUID): QueryId = uuid
  def of: IO[QueryId]                   = IO.randomUUID

opaque type SessionId <: UUID = UUID
object SessionId:
  inline def apply(uuid: UUID): SessionId = uuid
  def of: IO[SessionId]                   = IO.randomUUID

  given Eq[SessionId] = Eq.by(_.toString)

final case class ChatQuery(content: String)
object ChatQuery:
  // TODO: move this codec to controller or ... create dto?
  given FormDataDecoder[ChatQuery] = (
    field[String]("content")
  ).map(ChatQuery.apply)

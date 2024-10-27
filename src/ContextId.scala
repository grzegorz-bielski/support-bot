package supportbot

import java.util.UUID
import cats.effect.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

opaque type ContextId = UUID
object ContextId:
  inline def apply(uuid: UUID): ContextId = uuid
  def of: IO[ContextId]                   = IO.randomUUID

  given JsonValueCodec[ContextId] = JsonCodecMaker.make

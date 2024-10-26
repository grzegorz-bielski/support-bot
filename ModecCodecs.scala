package supportbot

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

object ModelCodecs:
  given JsonValueCodec[Model] =
    val strReader = JsonCodecMaker.make[String]

    new JsonValueCodec[Model]:
      def decodeValue(in: JsonReader, default: Model): Model =
        val decoded = strReader.decodeValue(in, default.name)

        Model.values
          .find(_.name == decoded)
          .getOrElse:
            // jsoniter parsing is not pure anyways...
            throw new IllegalArgumentException(s"Unknown model: $decoded")

      def encodeValue(x: Model, out: JsonWriter): Unit =
        strReader.encodeValue(x.name, out)

      def nullValue: Model = Model.Llama31
        // null.asInstanceOf[Model] // this is weird, maybe Circe wasn't so bad

package supportbot

/** Represents a LLM model
  *
  * @param name
  *   The name of the model like `llama3.1` or `llama3.1:8b-instruct-q4_0`. Has to be available in the target inference
  *   server like ollama.
  * @param contextLength
  *   The context length of the model. This is the maximum number of tokens that the model can process in a single
  *   request.
  */
enum Model(val name: String, val contextLength: Int):
  // `contextLength` defined by `num_ctx` in Modelfile. Cannot be set in ollama through OpenAI API
  case Llama31              extends Model("support-bot-llama", 31072)
  case SnowflakeArcticEmbed extends Model("snowflake-arctic-embed", 512)

object Model:
  export ModelCodecs.given

  lazy val defaultChatModel       = Model.Llama31
  lazy val defaultEmbeddingsModel = Model.SnowflakeArcticEmbed

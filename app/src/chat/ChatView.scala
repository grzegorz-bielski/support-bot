package supportbot
package chat

import scalatags.Text.all.*
import scala.concurrent.duration.{span as _, *}

object ChatView extends HtmxView:
  private val messagesViewId = "chat-messages"

  def view(chatPostUrl: String) = 
    div(
        cls := "rounded-box pl-2 md:col-span-2 md:bg-base-200",
        // div(cls := "divider", aria.hidden := true, "workbench"),
        messages(),
        chatForm(postUrl = chatPostUrl),
      )

  def chatForm(
    postUrl: String,
  ) =
    form(
      cls := "mr-2 pb-2",
      `hx-post`   := postUrl,
      `hx-target` := s"#$messagesViewId",
      `hx-swap`   := "beforeend scroll:bottom",
    )(
      div(
        input(
          cls         := "input input-bordered w-full p-2",
          `type`      := "text",
          name        := "content",
          placeholder := "Type a query to the chatbot",
        ),
      ),
    )

  def responseMessage(
    query: ChatQuery,
    sseUrl: String,
    queryResponseEvent: String,
    queryCloseEvent: String,
  ) =
    div(
      div(
        cls := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-secondary", query.content),
      ),
      div(
        cls := "chat chat-start",
        div(
          cls           := "chat-bubble chat-bubble-primary",
          `hx-ext`      := "sse",
          `sse-connect` := sseUrl,
          `sse-swap`    := queryResponseEvent,
          `sse-close`   := queryCloseEvent,
          `hx-swap`     := "beforeend scroll:bottom",
        )(),
      ),
    )

  def responseChunk(content: String) =
    span(sanitizeChunk(content))

  private def sanitizeChunk(input: String) =
    // TODO: this could be potentially dangerous, use a proper HTML sanitizer
    raw(
      input
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#x27;")
        .replaceAll("/", "&#x2F;")
        .replaceAll("\n", br().render),
    )

  def messages() =
    div(
      cls := "py-2 md:h-[calc(100dvh-12rem)] md:overflow-y-scroll",
      id := messagesViewId,
    )(
      div(
        cls := "chat chat-start",
        div(cls := "chat-bubble chat-bubble-primary", "Hello, how can I help you?"),
      ),
    )
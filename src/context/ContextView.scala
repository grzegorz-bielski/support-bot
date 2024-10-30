package supportbot
package context

import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import scalatags.Text.all.*
import scalatags.Text.tags2.{progress}

import context.chat.*

import supportbot.rag.*

object ContextView extends HtmxView:
  def view(
    context: ContextInfo,
    chatPostUrl: String,
    uploadUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
  )(using AppConfig) = RootLayoutView.view(
    div(
      configMenu(uploadUrl = uploadUrl, documents = documents, fileFieldName = fileFieldName),
      div(cls := "divider", aria.hidden := true),
      ChatView.messages(),
      ChatView.chatForm(postUrl = chatPostUrl),
    ),
  )

  def uploadedDocuments(docs: Vector[DocumentName]) = 
    ul(
      docs.map: doc =>
        li(
          doc,
        ),
    )

  def contextsOverview(contexts: Vector[ContextInfo])(using AppConfig) =
    RootLayoutView.view(
      div(
        div(
          cls := "flex justify-between items-center",
          h2(
            cls := "text-2xl p-5",
            "Your contexts",
          ),
          div(
            appLink(
              "/contexts/new",
              cls := "btn btn-primary",
              "Create new",
            ),
          ),
        ),
        div(
          ul(
            cls := "grid grid-cols-1 md:grid-cols-3 gap-4",
            contexts.map: context =>
              li(
                appLink(
                  s"/contexts/${context.id}",
                  div(
                    cls := "card bg-base-200 shadow-lg shadow-lg hover:shadow-xl transition-shadow",
                    div(
                      cls := "card-body",
                      h2(cls := "card-title", context.name),
                      p(context.description),
                    ),
                  ),
                ),
              ),
          ),
        ),
      ),
    )

  private def configMenu(uploadUrl: String, documents: Vector[Document.Info], fileFieldName: String) =
    div(
      cls := "grid grid-cols-1 md:grid-cols-2 gap-4 py-4",
      div(knowledgeBase(uploadUrl = uploadUrl, documents = documents, fileFieldName = fileFieldName)),
      div(promptSettings()),
      div(retrievalSettings()),
    )

  private def retrievalSettings() =
    collapse(
      opened = false,
      collapseTitle = "Retrieval Settings",
      collapseContent = div(
        form(
          cls := "form-control",
          label(
            cls         := "label",
            "Top K",
          ),
          input(
            cls         := "input",
            `type`      := "number",
            placeholder := "Type the number of top K",
          ),
          label(
            cls         := "label",
            "Fragment Lookup Range",
          ),
          input(
            cls         := "input",
            `type`      := "number",
            placeholder := "Type the number of fragment lookup range",
          ),
          button(
            cls         := "btn btn-primary",
            "Save",
          ),
        ),
      ),
    )

  private def promptSettings() =
    collapse(
      opened = false,
      collapseTitle = "Prompt Settings",
      collapseContent = div(
        form(
          cls := "form-control",
          label(
            cls         := "label",
            "Prompt Template",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt template here",
          ),
          label(
            cls         := "label",
            "Prompt Options",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt options here",
          ),
          label(
            cls         := "label",
            "Prompt Options",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt options here",
          ),
          button(
            cls         := "btn btn-primary",
            "Save",
          ),
        ),
      ),
    )

  private def knowledgeBase(
    uploadUrl: String,
    fileFieldName: String,
    documents: Vector[Document.Info],
  ) =
    collapse(
      opened = true,
      collapseTitle = "Knowledge Base",
      collapseContent = div(
        h3("Files"),
        ul(
          cls := "menu menu-xs bg-base-200 rounded-lg w-full max-w-s",
          documents.map: document =>
            li(
              a(
                documentIcon(),
                s"${document.name} - ${document.version}",
              ),
            ),
        ),
        modal(
          modalId = "uploadModal",
          buttonTitle = "Upload more",
          modalTitle = "Upload your files",
          modalContent = uploadForm(
            uploadUrl = uploadUrl,
            fileFieldName = fileFieldName,
          ),
          buttonExtraClasses = Vector("btn-secondary block ml-auto"),
        ),
      ),
    )

  private def modal(
    modalId: String,
    buttonTitle: String,
    modalTitle: String,
    modalContent: Modifier,
    buttonExtraClasses: Vector[String] = Vector.empty,
  ) =
    Vector(
      button(
        cls     := "btn " ++ buttonExtraClasses.mkString(" "),
        onclick := s"$modalId.showModal()",
        buttonTitle,
      ),
      dialog(
        id      := modalId,
        cls     := "modal modal-bottom sm:modal-middle",
        div(
          cls       := "modal-box",
          form(
            method := "dialog",
            button(
              cls        := "btn btn-sm btn-circle btn-ghost absolute right-2 top-2",
              aria.label := "close",
              "✕",
            ),
          ),
          h3(cls   := "text-lg font-bold", modalTitle),
          modalContent,
        ),
        form(method := "dialog", cls := "modal-backdrop", button("close")),
      ),
    )

  // https://uploadcare.com/blog/how-to-make-a-drag-and-drop-file-uploader/
  // https://http4s.org/v1/docs/multipart.html
  private def uploadForm(
    uploadUrl: String,
    fileFieldName: String,
  )            =
    form(
      id            := "upload-form",
      cls           := "w-full",
      `hx-post`     := uploadUrl,
      `hx-encoding` := "multipart/form-data",
      div(
        cls := "my-10",
        label(
          `for` := "dropzone-file",
          cls   := "form-control flex flex-col items-center justify-center w-full h-64 border-2 border-gray-300 border-dashed rounded-lg cursor-pointer bg-base-100 transition-colors hover:border-gray-400",
          span(
            cls          := "flex flex-col items-center justify-center pt-5 pb-6",
            span(uploadIcon()),
            p(
              cls := "mb-2 text-sm text-gray-500 dark:text-gray-400",
              span(
                cls    := "font-semibold",
                "Click to upload",
              ),
              span(cls := "ml-1", "or drag and drop"),
            ),
            p(cls := "text-xs text-gray-500 dark:text-gray-400", "PDF, TXT, HTML, Word, EPUB and more"),
          ),
          input(
            id           := "dropzone-file",
            `type`       := "file",
            attr("name") := fileFieldName,
            cls          := "hidden",
            multiple     := "true",
          ),
        ),
      ),
      button(
        cls := "btn btn-primary block ml-auto",
        "Upload",
      ),
    )

  private def collapse(
    collapseTitle: Modifier,
    collapseContent: Modifier,
    opened: Boolean = false,
  ) =
    val checkedAttr = Option.when(opened)(checked := true)

    div(
      cls := "collapse collapse-arrow border-base-300 bg-base-200 border",
      input(
        `type` := "checkbox",
        cls    := "peer w-full",
        checkedAttr,
      ),
      div(
        cls    := "collapse-title text-xl font-medium",
        collapseTitle,
      ),
      div(
        cls    := "collapse-content",
        collapseContent,
      ),
    )

  private def uploadIcon() =
    import scalatags.Text.svgTags.{attr as _, *}
    import scalatags.Text.svgAttrs.*

    svg(
      cls         := "w-8 h-8 mb-4 text-gray-500 dark:text-gray-400",
      aria.hidden := true,
      xmlns       := "http://www.w3.org/2000/svg",
      fill        := "none",
      viewBox     := "0 0 20 16",
      path(
        stroke                  := "currentColor",
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
        attr("stroke-width")    := "2",
        d                       := "M13 13h3a3 3 0 0 0 0-6h-.025A5.56 5.56 0 0 0 16 6.5 5.5 5.5 0 0 0 5.207 5.021C5.137 5.017 5.071 5 5 5a4 4 0 0 0 0 8h2.167M10 15V6m0 0L8 8m2-2 2 2",
      ),
    )

  private def documentIcon() =
    import scalatags.Text.svgTags.{attr as _, *}
    import scalatags.Text.svgAttrs.*

    svg(
      xmlns                := "http://www.w3.org/2000/svg",
      aria.hidden          := true,
      fill                 := "none",
      viewBox              := "0 0 24 24",
      attr("stroke-width") := "1.5",
      stroke               := "currentColor",
      cls                  := "h-4 w-4",
      path(
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
        d                       := "M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z",
      ),
    )
package supportbot

import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import scalatags.Text.all.*
import scalatags.Text.tags2.{progress, details, summary}

import supportbot.chat.*
import supportbot.rag.*
import supportbot.common.*
import supportbot.viewpartials.*

object ContextView extends HtmxView:
  private val uploadedFilesListId = "uploaded-files-list"
  private val uploadModalId       = "uploadModal"

  def view(
    contextInfo: ContextInfo,
    chatPostUrl: String,
    contextUpdateUrl: String,
    uploadUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
    documentDeleteUrl: DocumentDeleteUrl,
  )(using AppConfig) = RootLayoutView.view(
    div(
      cls := "grid grid-cols-1 md:grid-cols-5 gap-x-16 mt-4 md:mt-8",
      div(
        cls := "md:col-span-3",
        configMenu(
          uploadUrl = uploadUrl,
          contextUpdateUrl = contextUpdateUrl,
          documents = documents,
          fileFieldName = fileFieldName,
          contextInfo = contextInfo,
          documentDeleteUrl = documentDeleteUrl,
        ),
      ),
      ChatView.view(chatPostUrl = chatPostUrl),
    ),
  )

  def uploadedDocuments(docs: Vector[Document.Ingested], documentDeleteUrl: DocumentDeleteUrl) =
    ul(
      `hx-swap-oob` := s"beforeend:#$uploadedFilesListId",
      docs.map(ingested => documentItem(documentDeleteUrl)(ingested.info)),
    )

  def contextsOverview(contexts: Vector[ContextInfo], createNewUrl: String, contextUrl: ContextId => String)(using
    AppConfig,
  ) =
    RootLayoutView.view(
      div(
        cls := "mx-auto",
        div(
          cls := "flex justify-between items-center bg-base-200 p-5 rounded-box my-4 md:my-8",
          h2(
            cls := "text-2xl",
            "Your contexts",
          ),
          div(
            appLink(
              createNewUrl,
              cls := "btn btn-primary",
              "Create new",
            ),
          ),
        ),
        div(
          ul(
            cls := "grid grid-cols-1 md:grid-cols-2 gap-6 auto-rows-fr justify-between",
            contexts.map(contextCard(_, contextUrl)),
          ),
        ),
      ),
    )

  private def contextCard(context: ContextInfo, contextUrl: ContextId => String) =
    val contextCardId = s"context-${context.id}"

    li(
      cls := "block",
      id  := contextCardId,
      div(
        cls := "card h-full bg-base-100 shadow-lg shadow-xl transition-shadow",
        div(
          cls := "card-body",
          h2(cls := "card-title", context.name),
          p(context.description),
          div(
            cls  := "join mr-0 ml-auto",
            appLink(
              path = contextUrl(context.id),
              child = "Edit",
              attrs = cls := "btn btn-outline rounded-btn join-item",
            ),
            div(
              cls := "dropdown dropdown-end",
              div(
                tabindex := "0",
                role     := "button",
                cls      := "btn btn-outline join-item rounded-btn",
                IconsView.arrowDownIcon(),
              ),
              ul(
                tabindex := "0",
                cls      := "menu join join-vertical dropdown-content bg-base-100 rounded-box z-[1] mt-1 w-52 p-2 shadow",
                li(
                  button(
                    cls := "join-item btn btn-disabled btn-sm w-full",
                    "Disable",
                  ),
                ),
                li(
                  button(
                    cls         := "join-item btn btn-sm btn-error w-full",
                    `hx-delete` := contextUrl(context.id),
                    `hx-target` := s"#$contextCardId",
                    `hx-swap`   := "outerHTML",
                    "Delete",
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )

  private def configMenu(
    uploadUrl: String,
    contextUpdateUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
    contextInfo: ContextInfo,
    documentDeleteUrl: DocumentDeleteUrl,
  ) =
    div(
      role := "tablist",
      cls  := "tabs tabs-bordered",
      tab(
        "Knowledge Base",
        knowledgeBase(
          uploadUrl = uploadUrl,
          documents = documents,
          fileFieldName = fileFieldName,
          documentDeleteUrl = documentDeleteUrl,
        ),
        checked = true,
      ),
      tab("Context Settings", contextSettings(contextInfo = contextInfo, contextUpdateUrl = contextUpdateUrl)),
    )

  private def tab(name: String, content: Modifier, checked: Boolean = false) =
    Seq(
      input(
        `type`             := "radio",
        attr("name")       := "my_tabs_1",
        role               := "tab",
        cls                :=
          Vector(
            "tab",
            "rounded-box",
            "min-w-36",
            "focus:[box-shadow:none]",
            "border-t-0",
            "border-x-0",
            "bg-transparent",
            "checked:bg-none",
            "checked:bg-transparent",
            "checked:hover:bg-transparent",
            "checked:focus:bg-transparent",
            "checked:hover:border-current",
            "checked:focus:border-current",
          )
            .mkString(" "),
        attr("aria-label") := name,
        Option.when(checked)(attr("checked") := "checked"),
      ),
      div(
        role               := "tabpanel",
        cls                := "tab-content bg-base-100 pt-2 md:pt-6 md:h-[calc(100dvh-16rem)] overflow-y-scroll",
        content,
      ),
    )

  private def contextSettings(
    contextInfo: ContextInfo,
    contextUpdateUrl: String,
  ) =
    val promptTemplateJson =
      contextInfo.promptTemplate.asJson(indentStep = 2).combineAll

    val retrievalSettingsJson =
      contextInfo.retrievalSettings.asJson(indentStep = 2).combineAll

    val chatCompletionSettingsJson =
      contextInfo.chatCompletionSettings.asJson(indentStep = 2).combineAll

    div(
      form(
        `hx-post` := contextUpdateUrl,
        `hx-swap` := "none",
        div(
          cls := "grid grid-cols-1 md:grid-cols-2 gap-2",
          formInput(
            labelValue = "Name",
            fieldName = "name",
            value = contextInfo.name,
          ),
          formInput(
            labelValue = "Description",
            fieldName = "description",
            value = contextInfo.description,
          ),
        ),
        formTextarea(
          labelValue = "Prompt Template",
          fieldName = "promptTemplate",
          value = promptTemplateJson,
        ),
        formTextarea(
          labelValue = "Retrieval Settings",
          fieldName = "retrievalSettings",
          value = retrievalSettingsJson,
        ),
        formTextarea(
          labelValue = "Chat Completion Settings",
          fieldName = "chatCompletionSettings",
          value = chatCompletionSettingsJson,
        ),
        div(
          cls := "grid grid-cols-1 md:grid-cols-2 gap-2",
          formSelect(
            labelValue = "Chat Model",
            fieldName = "chatModel",
            options = modelOptions(contextInfo.chatModel),
          ),
          formSelect(
            labelValue = "Embeddings Model",
            fieldName = "embeddingsModel",
            options = modelOptions(contextInfo.embeddingsModel),
          ),
        ),
        button(
          cls := "btn btn-secondary block ml-auto mt-2",
          "Save",
        ),
      ),
    )

  private def formTextarea(labelValue: String, fieldName: String, value: String) =
    formControl(
      labelValue,
      textarea(
        cls  := "textarea textarea-bordered w-full h-64",
        name := fieldName,
        value,
      ),
    )

  private def formInput(labelValue: String, fieldName: String, value: String) =
    formControl(
      labelValue,
      input(
        cls           := "input input-bordered w-full",
        name          := fieldName,
        attr("value") := value,
      ),
    )

  final case class SelectOption(label: String, value: String, selected: Boolean = false)

  private def formSelect(labelValue: String, fieldName: String, options: Vector[SelectOption]) =
    formControl(
      labelValue,
      select(
        cls  := "select select-bordered w-full",
        name := fieldName,
        options.map: op =>
          option(
            value := op.value,
            Option.when(op.selected)(selected := true),
            op.label,
          ),
      ),
    )

  private def modelOptions(current: Model) = Model.values.toVector.map: model =>
    SelectOption(
      label = model.name,
      value = model.name,
      selected = model == current,
    )

  private def formControl(labelValue: String, input: Modifier) =
    label(
      cls := "form-control w-full",
      div(
        cls := "label",
        span(
          cls := "label-text",
          labelValue,
        ),
      ),
      input,
    )

  type DocumentDeleteUrl = Document.Info => String

  private def documentItem(documentDeleteUrl: DocumentDeleteUrl)(document: Document.Info) =
    val documentFileId = s"file-${document.id}"

    li(
      id  := documentFileId,
      cls := "group rounded-r-box hover:bg-base-300 focus-within:bg-base-300 outline-none mr-5",
      div(
        cls := "min-h-8 py-2 px-3 text-xs flex gap-3 items-center",
        span(IconsView.documentIcon()),
        span(cls      := "text-wrap break-all", s"${document.name} - v${document.version}"),
        button(
          `hx-delete` := documentDeleteUrl(document),
          `hx-target` := s"#$documentFileId",
          `hx-swap`   := "outerHTML",
          cls         := "btn btn-xs btn-ghost btn-square opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 mr-0 ml-auto transition-none",
          "✕",
        ),
      ),
    )

  private def emptyItem() =
    li(
      cls := "hidden last:block",
      div(
        cls := "px-4 py-8 flex justify-center",
        p("Nothing here yet."),
      ),
    )

  private def knowledgeBase(
    uploadUrl: String,
    fileFieldName: String,
    documents: Vector[Document.Info],
    documentDeleteUrl: DocumentDeleteUrl,
  ) =

    val files =
      div(
        ul(
          cls := "max-h-96 overflow-y-scroll",
          id  := uploadedFilesListId,
          emptyItem(),
          documents.map(documentItem(documentDeleteUrl)),
        ),
      )

    val uploadFilesModal = ModalView.view(
      modalId = uploadModalId,
      buttonTitle = "Upload",
      modalTitle = "Upload your files",
      modalContent = uploadForm(
        uploadUrl = uploadUrl,
        fileFieldName = fileFieldName,
      ),
      buttonExtraClasses = Vector("btn-secondary block ml-auto"),
    )

    div(
      ul(
        cls := "bg-base-200 rounded-lg w-full max-w-s mb-4",
        li(
          details(
            attr("open") := true,
            summary(
              cls := Vector(
                "p-4 cursor-pointer rounded-lg",
                "hover:bg-base-300 active:bg-base-400 focus:bg-base-400 outline-none transition-colors",
              ).mkString(" "),
              "Files",
              // TODO: add folder icon to the right, right now it breaks the summary marker
              // folderIcon(),
            ),
            files,
            div(
              cls := "p-5",
              uploadFilesModal.button,
            ),
          ),
        ),
      ),
      uploadFilesModal.window,
    )

  // https://uploadcare.com/blog/how-to-make-a-drag-and-drop-file-uploader/
  // https://http4s.org/v1/docs/multipart.html
  private def uploadForm(uploadUrl: String, fileFieldName: String) =
    fileUploader(
      attr("upload-url")      := uploadUrl,
      attr("file-field-name") := fileFieldName,
      attr("modal-id")        := uploadModalId,
      attr("max-size")        := "1073741824", // 1GiB
      attr("allowed-types")   :=
        Vector(
          // General
          "application/pdf",
          "text/plain",
          "text/html",
          "text/csv",
          "text/xml",
          "application/rtf",
          "application/json",
          "application/xml",
          "application/xhtml+xml",
          // MS
          "application/vnd.ms-excel",
          "application/msword",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation",
          // OpenOffice
          "application/vnd.ms-powerpoint",
          "application/x-vnd.oasis.opendocument.spreadsheet",
          "application/vnd.oasis.opendocument.spreadsheet",
          "application/vnd.oasis.opendocument.presentation",
          "application/vnd.oasis.opendocument.text",
          // Other
          "application/x-abiword",
          // Ebooks
          "application/epub+zip",
          "application/x-mobipocket-ebook",
        ).mkString(","),
    )

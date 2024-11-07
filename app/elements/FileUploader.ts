// TODO:
// - disable upload button if there are validation errors

type Nullable<T> = T | null | undefined;
type HTMXProgressEvent = Event & {
  detail: { loaded: number; total: number; lengthComputable: number };
};

class FileUploader extends HTMLElement {
  readonly #allowedFileTypes?: string[];
  readonly #maxFileSize?: number;
  readonly #uploadUrl: string;
  readonly #fileFieldName: string;
  readonly #root: HTMLElement;

  static get observedAttributes(): string[] {
    return ["allowed-types", "max-file-size", "upload-url", "file-field-name"];
  }

  get #uploadForm() {
    return this.#root.querySelector<HTMLFormElement>("#upload-form");
  }

  get #dropZone() {
    return this.#root.querySelector<HTMLElement>("#dropZone");
  }

  get #fileInput() {
    return this.#root.querySelector<HTMLInputElement>("#dropzone-file");
  }

  get #selectedFiles() {
    return Array.from(this.#fileInput?.files ?? []);
  }

  get #uploadButton() {
    return this.#root.querySelector<HTMLButtonElement>("#uploadButton");
  }

  get #progress() {
    return this.#root.querySelector<HTMLElement>("#progress");
  }

  get #fileList() {
    return this.#root.querySelector<HTMLElement>("#fileList");
  }

  constructor() {
    super();

    this.#root = this;

    this.#allowedFileTypes = this.getAttribute("allowed-types")?.split(",");
    this.#maxFileSize = Number(this.getAttribute("max-file-size")) || undefined;
    this.#uploadUrl = this.getAttribute("upload-url") || "";
    this.#fileFieldName = this.getAttribute("file-field-name") || "files";

    const uploadIcon = `
      <svg
        class="w-8 h-8 mb-4 text-gray-500 dark:text-gray-400"
        aria-hidden="true"
        xmlns="http://www.w3.org/2000/svg"
        fill="none"
        viewBox="0 0 20 16"
      >
        <path
          stroke="currentColor"
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M13 13h3a3 3 0 0 0 0-6h-.025A5.56 5.56 0 0 0 16 6.5 5.5 5.5 0 0 0 5.207 5.021C5.137 5.017 5.071 5 5 5a4 4 0 0 0 0 8h2.167M10 15V6m0 0L8 8m2-2 2 2"
        />
      </svg>
    `;

    this.#root.innerHTML = `
        <form 
          id="upload-form" 
          class="w-full" 
          hx-post="${this.#uploadUrl}" 
          hx-encoding="multipart/form-data"
        >
          <div class="mt-10">
            <label 
              id="dropZone" 
              for="dropzone-file" 
              class="form-control flex flex-col items-center justify-center w-full h-64 border-2 border-gray-300 border-dashed rounded-lg cursor-pointer bg-base-100 transition-colors hover:border-gray-400"
            >
              <span class="flex flex-col items-center justify-center pt-5 pb-6">
                <span>${uploadIcon}</span>
                <p class="mb-2 text-sm text-gray-500 dark:text-gray-400">
                  <span class="font-semibold">Click to upload</span>
                  <span class="ml-1">or drag and drop</span>
                </p>
                <p class="text-xs text-gray-500 dark:text-gray-400">
                  PDF, TXT, HTML, Word, EPUB and more
                </p>
              </span>
              <input 
                id="dropzone-file" 
                type="file" 
                name="${this.#fileFieldName}" 
                class="hidden"
                multiple
              >
            </label>
          </div>
          <progress id="progress" class="progress progress-secondary opacity-0" value="0" max="100"></progress>
          <ul class="pb-2" id="fileList"></ul>
          <button id="uploadButton" class="btn btn-primary btn-disabled block ml-auto">Upload</button>
        </form>
        
        <div id="uploadResponse"></div>
      `;
  }

  connectedCallback(): void {
    const dropZone = this.#dropZone;
    const fileInput = this.#fileInput;
    const uploadForm = this.#uploadForm;
    const fileList = this.#fileList;

    if (dropZone && uploadForm && fileInput && fileList) {
      dropZone.addEventListener("dragover", this.#handleDragOver);
      dropZone.addEventListener("dragleave", this.#handleDragLeave);
      dropZone.addEventListener("drop", this.#handleDrop);
      fileInput.addEventListener("change", this.#handleFileSelect);
      uploadForm.addEventListener("submit", this.#handleUpload);
      uploadForm.addEventListener("htmx:xhr:progress", this.#handleProgress);
      fileList.addEventListener("click", this.#handleFileRemove);
    }
  }

  #handleDragOver = (e: DragEvent): void => {
    e.preventDefault();
    e.stopPropagation();

    this.#dropZone?.classList.add("drag-over");
  };

  #handleDragLeave = (e: DragEvent): void => {
    e.preventDefault();
    e.stopPropagation();

    this.#dropZone?.classList.remove("drag-over");
  };

  #handleDrop = (e: DragEvent): void => {
    e.preventDefault();
    e.stopPropagation();

    this.#dropZone?.classList.remove("drag-over");

    this.#addFiles(e.dataTransfer?.files);
  };

  #handleFileSelect = (): void => {
    this.#addFiles(this.#fileInput?.files);
  };

  #handleFileRemove = (e: Event): void => {
    e.preventDefault();
    e.stopPropagation();
    console.log("remove file", e);
    if (!e.target) return;

    const target = e.target as HTMLElement;
    const fileName = target.getAttribute("data-remove-file");

    console.log("fileName", fileName);

    if (!fileName) return;

    this.#removeFileFromInput(fileName);
    this.#updateFileList();
    this.#updateUploadButtonVisibility();
  };

  #validateFile(file: File): { valid: boolean; error?: string } {
    if (this.#allowedFileTypes && !this.#allowedFileTypes.includes(file.type)) {
      return {
        valid: false,
        error: `File type ${file.type} is not allowed.`,
      };
    }

    if (this.#maxFileSize && file.size > this.#maxFileSize) {
      return {
        valid: false,
        error: `File size ${formatFileSize(
          file.size
        )} exceeds maximum size of ${formatFileSize(this.#maxFileSize)}`,
      };
    }

    return { valid: true };
  }

  #addFiles(files: Nullable<FileList>): void {
    console.log("addFiles", files);

    if (!files) return;

    this.#updateFileList();
  }

  #removeFileFromInput(fileName: String): void {
    const dataTransfer = new DataTransfer();

    this.#selectedFiles.forEach((file) => {
      if (file.name !== fileName) {
        dataTransfer.items.add(file);
      }
    });

    const fileInput = this.#fileInput;
    if (fileInput) {
      fileInput.files = dataTransfer.files;
    }
  }

  #updateUploadButtonVisibility(force?: boolean): void {
    this.#uploadButton?.classList.toggle(
      "btn-disabled",
      this.#selectedFiles.length === 0
    );
  }

  #updateFileList(): void {
    const fileList = this.#fileList
    if (!fileList) return;

    fileList.innerHTML = "";

    for (const file of this.#selectedFiles) {
      const validationResult = this.#validateFile(file);

      const errorClasses = validationResult.error ? "bg-error text-error-500" : ""

      const row = `
        <li class="flex justify-between items-center bg-base-200 rounded-box p-2 my-1 ${errorClasses}">
          <div class="text-sm overflow-auto">
            ${validationResult.error ? `<div class="mt-0.5 text-white">${validationResult.error}</div>` : ""}
            <div class="text-wrap break-words">${file.name} (${formatFileSize(file.size)})</div>
          </div>
          <button class="btn btn-sm btn-outline btn-circle" data-remove-file="${file.name}">x</button>
        </li>
      `;

      fileList.insertAdjacentHTML("beforeend", row);
    }

    this.#updateUploadButtonVisibility();
  }

  #handleProgress = (e: Event) => {
    const event = e as HTMXProgressEvent;
    console.log("handleProgress", event);
    const progress = (event.detail.loaded / event.detail.total) * 100
    this.#progress?.setAttribute("value", progress.toString());
  }

  #handleUpload = () => {
    console.log("handleUpload");

    this.#progress?.classList.remove("opacity-0");
  };
}

customElements.define("file-uploader", FileUploader);

function formatFileSize(bytes: number): string {
  if (bytes === 0) {
    return "0 Bytes";
  }

  const k = 1024;
  const sizes = ["Bytes", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return (
    Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i]
  );
}

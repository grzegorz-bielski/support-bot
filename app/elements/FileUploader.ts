// Define interfaces and types
interface UploadProgressEvent extends CustomEvent {
  detail: {
    files: File[];
  };
}

interface FileUploaderEventMap {
  "upload-complete": UploadProgressEvent;
}

type ProgressCallback = (progress: number) => void;

export class FileUploader extends HTMLElement {
  private fileInput: HTMLInputElement;
  private selectedFiles: Set<File>;
  private readonly allowedFileTypes?: string[];
  private readonly maxFileSize?: number;
  #shadowRoot: ShadowRoot;

  static get observedAttributes(): string[] {
    return ["allowed-types", "max-file-size"];
  }

  constructor() {
    super();

    // Create a shadow DOM
    this.#shadowRoot = this.attachShadow({ mode: "open" });

    // Initialize properties
    this.selectedFiles = new Set<File>();
    this.allowedFileTypes = this.getAttribute("allowed-types")?.split(",");
    this.maxFileSize = Number(this.getAttribute("max-file-size")) || undefined;

    // Define the component's HTML structure
    this.#shadowRoot.innerHTML = `
        <style>
          :host {
            display: block;
            max-width: 42rem;
            margin: 0 auto;
            font-family: system-ui, -apple-system, sans-serif;
          }
          
          .file-drop-zone {
            border: 2px dashed #E5E7EB;
            border-radius: 0.5rem;
            padding: 2rem;
            text-align: center;
            cursor: pointer;
            transition: border-color 0.3s ease;
          }
          
          .file-drop-zone.drag-over {
            border-color: #6B7280;
            background-color: #F9FAFB;
          }
          
          .upload-icon {
            margin: 0 auto;
            width: 3rem;
            height: 3rem;
            color: #9CA3AF;
          }
          
          .progress-container {
            margin-top: 1rem;
            display: none;
          }
          
          .progress-track {
            width: 100%;
            height: 0.5rem;
            background-color: #E5E7EB;
            border-radius: 0.25rem;
            overflow: hidden;
            margin-top: 0.5rem;
          }
          
          .progress-bar {
            width: 0%;
            height: 100%;
            background-color: #3B82F6;
            transition: width 0.3s ease;
          }
          
          .file-list {
            margin-top: 1rem;
          }
          
          .file-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 0.5rem;
            border: 1px solid #E5E7EB;
            margin-bottom: 0.5rem;
            border-radius: 0.25rem;
          }
          
          .file-item button {
            background: none;
            border: none;
            color: #EF4444;
            cursor: pointer;
            padding: 0.25rem 0.5rem;
          }
          
          .upload-button {
            margin-top: 1rem;
            padding: 0.5rem 1rem;
            background-color: #3B82F6;
            color: white;
            border: none;
            border-radius: 0.25rem;
            cursor: pointer;
            font-size: 1rem;
          }
          
          .upload-button:hover {
            background-color: #2563EB;
          }
          
          .error-message {
            color: #EF4444;
            margin-top: 0.5rem;
          }
        </style>
        
        <div class="file-drop-zone" id="dropZone">
          <svg class="upload-icon" stroke="currentColor" fill="none" viewBox="0 0 48 48">
            <path d="M28 8H12a4 4 0 00-4 4v20m0 0v4a4 4 0 004 4h20a4 4 0 004-4V28m-4-4l-8-8-4 4" 
                  stroke-width="2" 
                  stroke-linecap="round" 
                  stroke-linejoin="round" />
          </svg>
          <div>
            <p>Drop files here or click to upload</p>
            <p>Drag and drop your files anywhere or click to browse</p>
          </div>
        </div>
        
        <div class="progress-container" id="progressContainer">
          <div class="progress-label">Uploading</div>
          <div class="progress-track">
            <div class="progress-bar" id="progressBar"></div>
          </div>
        </div>
        
        <div class="file-list" id="fileList"></div>
        
        <button class="upload-button" id="uploadButton">Upload Files</button>
        
        <div id="uploadResponse"></div>
      `;

    // Create a hidden file input
    this.fileInput = document.createElement("input");
    this.fileInput.type = "file";
    this.fileInput.multiple = true;
    this.fileInput.style.display = "none";
    this.#shadowRoot.appendChild(this.fileInput);
  }

  connectedCallback(): void {
    const dropZone = this.#shadowRoot.getElementById("dropZone");
    const uploadButton = this.#shadowRoot.getElementById("uploadButton");

    if (dropZone && uploadButton) {
      dropZone.addEventListener("click", () => this.fileInput.click());
      dropZone.addEventListener("dragover", this.handleDragOver.bind(this));
      dropZone.addEventListener("dragleave", this.handleDragLeave.bind(this));
      dropZone.addEventListener("drop", this.handleDrop.bind(this));
      this.fileInput.addEventListener(
        "change",
        this.handleFileSelect.bind(this)
      );
      uploadButton.addEventListener("click", this.handleUpload.bind(this));
    }
  }

  private handleDragOver(e: DragEvent): void {
    e.preventDefault();
    e.stopPropagation();
    const dropZone = this.#shadowRoot.getElementById("dropZone");
    if (dropZone) {
      dropZone.classList.add("drag-over");
    }
  }

  private handleDragLeave(e: DragEvent): void {
    e.preventDefault();
    e.stopPropagation();
    const dropZone = this.#shadowRoot.getElementById("dropZone");
    if (dropZone) {
      dropZone.classList.remove("drag-over");
    }
  }

  private handleDrop(e: DragEvent): void {
    e.preventDefault();
    e.stopPropagation();
    const dropZone = this.#shadowRoot.getElementById("dropZone");
    if (dropZone) {
      dropZone.classList.remove("drag-over");
    }

    const files = e.dataTransfer?.files;
    if (files) {
      this.addFiles(files);
    }
  }

  private handleFileSelect(e: Event): void {
    const files = (e.target as HTMLInputElement).files;
    if (files) {
      this.addFiles(files);
    }
  }

  private validateFile(file: File): { valid: boolean; error?: string } {
    if (this.allowedFileTypes && !this.allowedFileTypes.includes(file.type)) {
      return {
        valid: false,
        error: `File type ${
          file.type
        } is not allowed. Allowed types: ${this.allowedFileTypes.join(", ")}`,
      };
    }

    if (this.maxFileSize && file.size > this.maxFileSize) {
      return {
        valid: false,
        error: `File size ${this.formatFileSize(
          file.size
        )} exceeds maximum size of ${this.formatFileSize(this.maxFileSize)}`,
      };
    }

    return { valid: true };
  }

  private addFiles(files: FileList): void {
    const errorContainer =
      this.#shadowRoot.querySelector(".error-message") ||
      this.#shadowRoot.appendChild(document.createElement("div"));
    errorContainer.className = "error-message";
    errorContainer.textContent = "";

    for (const file of files) {
      const validation = this.validateFile(file);
      if (validation.valid) {
        this.selectedFiles.add(file);
      } else if (validation.error) {
        errorContainer.textContent = validation.error;
      }
    }

    this.updateFileList();
  }

  private removeFile(file: File): void {
    this.selectedFiles.delete(file);
    this.updateFileList();
  }

  private updateFileList(): void {
    const fileList = this.#shadowRoot.getElementById("fileList");
    if (!fileList) return;

    fileList.innerHTML = "";

    for (const file of this.selectedFiles) {
      const fileElement = document.createElement("div");
      fileElement.className = "file-item";

      const fileInfo = document.createElement("span");
      fileInfo.textContent = `${file.name} (${this.formatFileSize(file.size)})`;

      const removeButton = document.createElement("button");
      removeButton.textContent = "×";
      removeButton.addEventListener("click", () => this.removeFile(file));

      fileElement.appendChild(fileInfo);
      fileElement.appendChild(removeButton);
      fileList.appendChild(fileElement);
    }
  }

  private formatFileSize(bytes: number): string {
    if (bytes === 0) return "0 Bytes";

    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  }

  private updateProgress(progress: number): void {
    const progressBar = this.#shadowRoot.getElementById("progressBar");
    if (progressBar) {
      progressBar.style.width = `${progress}%`;
    }
  }

  private async simulateUpload(onProgress: ProgressCallback): Promise<void> {
    for (let progress = 0; progress <= 100; progress += 10) {
      onProgress(progress);
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
  }

  private async handleUpload(): Promise<void> {
    if (this.selectedFiles.size === 0) {
      return;
    }

    const progressContainer =
      this.#shadowRoot.getElementById("progressContainer");
    const uploadResponse = this.#shadowRoot.getElementById("uploadResponse");

    if (!progressContainer || !uploadResponse) return;

    progressContainer.style.display = "block";

    try {
      // Simulate upload - replace with actual upload logic
      await this.simulateUpload(this.updateProgress.bind(this));

      // Dispatch custom event on successful upload
      const event = new CustomEvent("upload-complete", {
        detail: {
          files: Array.from(this.selectedFiles),
        },
      });
      this.dispatchEvent(event);

      uploadResponse.textContent = "Upload completed successfully!";
      uploadResponse.className = "";
      this.selectedFiles.clear();
      this.updateFileList();
    } catch (error) {
      uploadResponse.textContent = "Upload failed. Please try again.";
      uploadResponse.className = "error-message";
    } finally {
      setTimeout(() => {
        progressContainer.style.display = "none";
        this.updateProgress(0);
      }, 1000);
    }
  }

  // Method to implement actual file upload
  private async uploadFiles(files: File[]): Promise<void> {
    const formData = new FormData();
    files.forEach((file) => formData.append("files", file));

    const response = await fetch("/api/upload", {
      method: "POST",
      body: formData,
    });

    if (!response.ok) {
      throw new Error("Upload failed");
    }
  }
}

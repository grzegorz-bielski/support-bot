import './htmx.ts'
import 'htmx-ext-sse'


import { FileUploader } from './FileUploader';

customElements.define('file-uploader', FileUploader);

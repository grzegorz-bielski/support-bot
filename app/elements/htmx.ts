import htmx from 'htmx.org'

// any htmx extensions should be added only after this file is imported
declare global {
  interface Window {
	htmx: typeof htmx;
  }
}

window.htmx = htmx;

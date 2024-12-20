/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/main/**/*.scala",
    "./elements/**/*.ts"
  ],
  theme: {
    extend: {},
  },
  plugins: [
    require("@tailwindcss/forms"),
    require("@tailwindcss/typography"),
    require("daisyui"),
  ],
  daisyui: {
    themes: ["light", "dark", "cupcake", "cmyk"],
  },
};

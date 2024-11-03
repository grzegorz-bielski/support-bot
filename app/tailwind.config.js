/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.scala",
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
    themes: ["light", "dark", "cupcake"],
  },
};

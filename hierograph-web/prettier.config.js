//  @ts-check

/** @type {import('prettier').Config} */
const config = {
  plugins: ["prettier-plugin-tailwindcss"],
  tailwindFunctions: ["clsx", "twMerge"],
};

export default config;

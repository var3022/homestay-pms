import fs from "node:fs";
import path from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";

const extensions = [".ts", ".tsx", ".js", ".mjs", "/index.ts", "/index.tsx", "/index.js"];

export async function resolve(specifier, context, defaultResolve) {
  // Handle Next.js server import
  if (specifier === "next/server") {
    return defaultResolve("next/server.js", context);
  }

  // Handle path alias @/
  if (specifier.startsWith("@/")) {
    const relativePath = specifier.slice(2);
    const basePath = path.resolve("src", relativePath);
    for (const ext of extensions) {
      const fullPath = basePath + ext;
      if (fs.existsSync(fullPath) && !fs.statSync(fullPath).isDirectory()) {
        return {
          shortCircuit: true,
          url: pathToFileURL(fullPath).href,
        };
      }
    }
  }

  // Handle relative imports without extension
  if ((specifier.startsWith("./") || specifier.startsWith("../")) && !path.extname(specifier)) {
    const parentDir = context.parentURL
      ? path.dirname(fileURLToPath(context.parentURL))
      : process.cwd();
    const basePath = path.resolve(parentDir, specifier);
    for (const ext of extensions) {
      const fullPath = basePath + ext;
      if (fs.existsSync(fullPath) && !fs.statSync(fullPath).isDirectory()) {
        return {
          shortCircuit: true,
          url: pathToFileURL(fullPath).href,
        };
      }
    }
  }

  return defaultResolve(specifier, context);
}

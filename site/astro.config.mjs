import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://herdr-os.org",
  build: { inlineStylesheets: "auto" },
  // Previewed over Tailscale by its MagicDNS name.
  vite: { preview: { allowedHosts: [".ts.net"] } },
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';

// viteSingleFile inlines JS/CSS so the build opens straight from disk (HashRouter keeps routing working).
// Remove the plugin for a normal multi-chunk production build.
export default defineConfig({ plugins: [react(), viteSingleFile()], build: { chunkSizeWarningLimit: 4000 } });

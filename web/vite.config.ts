import adapter from '@sveltejs/adapter-auto';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},

			// The dashboard runs as the Vite dev server on the demo laptop (`npm run dev`); no build is deployed.
			adapter: adapter()
		})
	],
	server: {
		port: 5180,
		strictPort: true,
		// The FastAPI side (laptop/dashboard/app.py) owns /api; same origin for the page, no CORS.
		proxy: {
			'/api': 'http://127.0.0.1:8081'
		}
	}
});

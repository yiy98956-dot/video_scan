import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({
    plugins: [tailwindcss()],
    root: 'src',
    server: {
        port: 5174,
        strictPort: true,
        host: '0.0.0.0',
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
    build: { outDir: '../dist' },
});

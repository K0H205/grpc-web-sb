import { defineConfig } from "vite";

export default defineConfig({
  server: {
    // dev 時は Vite から Spring Boot へプロキシして同一オリジン扱いにする (CORS 不要)
    proxy: {
      "/grpc-web": "http://localhost:8080",
    },
  },
});

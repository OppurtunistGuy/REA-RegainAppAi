# syntax=docker/dockerfile:1
#
# Build from the backend/ directory, passing the repo's shared/ folder as a named context
# (BuildKit, Docker 23+):
#
#   docker build --build-context shared=../shared -t steady-backend .
#   docker run -p 8787:8787 -v steady-data:/data steady-backend
#
# The detector config (shared/detectors) is baked into the image at /app/shared/detectors.

FROM node:22-slim AS build
WORKDIR /app/backend
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:22-slim
ENV NODE_ENV=production \
    PORT=8787 \
    HOST=0.0.0.0 \
    DATABASE_PATH=/data/steady.db \
    DETECTORS_PATH=/app/shared/detectors/detectors.v1.json
WORKDIR /app/backend
COPY --from=build --chown=root:root /app/backend/package.json ./
COPY --from=build --chown=root:root /app/backend/node_modules ./node_modules
COPY --from=build --chown=root:root /app/backend/dist ./dist
COPY --from=shared --chown=root:root detectors /app/shared/detectors
# Code is root-owned and read-only for the app user; only /data is writable.
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/v1/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "--disable-warning=ExperimentalWarning", "dist/server.js"]

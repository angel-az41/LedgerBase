# ============================================================
# LEDGERBASE — REACT FRONTEND CONTAINER
# ============================================================
#
# PURPOSE:
#
# Build and serve the React + TypeScript frontend.
#
# BUILD STRATEGY:
#
# - Node.js installs dependencies and builds the app.
# - Buildx runs Node.js on the builder's native architecture.
# - Nginx serves the generated static assets.
# - Spring Cloud Gateway handles backend API routing.
#
# MULTI-ARCHITECTURE SUPPORT:
#
# - The Node.js build stage uses BUILDPLATFORM to avoid
#   slow QEMU emulation during npm installation.
# - The Nginx runtime stage uses the target architecture.
# - Buildx can produce linux/amd64 and linux/arm64 images.
#
# The final image does not contain Node.js or the
# frontend development dependencies.


# ============================================================
# BUILD STAGE — NATIVE BUILD PLATFORM
# ============================================================

# Run dependency installation and frontend compilation
# on the Docker builder's native CPU architecture.
#
# React/Vite produces platform-independent static assets.
# No Node.js binaries are copied into the runtime stage.
#
# This avoids ARM64-emulated npm ci on AMD64 CI runners.

FROM --platform=$BUILDPLATFORM node:22-alpine AS build

WORKDIR /app

# Copy dependency manifests first so Docker can
# reuse the dependency layer when source files change.
COPY package.json package-lock.json ./

# Install exact dependency versions from the lockfile.
RUN npm ci

# Copy frontend source and configuration.
COPY . .

# Compile TypeScript and build optimized assets.
RUN npm run build


# ============================================================
# RUNTIME STAGE — TARGET PLATFORM
# ============================================================

# Buildx selects the correct Nginx image architecture
# for each requested target platform.
#
# Do not force BUILDPLATFORM here: the runtime image
# must match the target architecture.

FROM nginx:stable-alpine AS runtime

# Replace the default Nginx configuration with
# LedgerBase's SPA routing and API proxy configuration.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy only the production frontend assets.
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx listens on port 80 inside the container.
EXPOSE 80

# Keep Nginx in the foreground for Docker lifecycle
# management and container logging.
CMD ["nginx", "-g", "daemon off;"]

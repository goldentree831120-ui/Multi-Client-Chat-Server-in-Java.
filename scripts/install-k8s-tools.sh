#!/usr/bin/env bash
# Installs kind v0.29.0 (Kubernetes in Docker) and kubectl v1.33.1 into ~/.local/bin, checking
# each download against its published SHA-256 checksum. No sudo needed. Safe to run twice.
set -euo pipefail
KIND_VERSION=v0.29.0
KUBECTL_VERSION=v1.33.1
BIN="$HOME/.local/bin"
mkdir -p "$BIN"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if ! command -v kind >/dev/null || ! kind version | grep -q "$KIND_VERSION"; then
  base="https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION"
  curl -fsSL -o "$tmp/kind" "$base/kind-linux-amd64"
  expected=$(curl -fsSL "$base/kind-linux-amd64.sha256sum" | awk '{print $1}')
  echo "$expected  $tmp/kind" | sha256sum -c -
  install -m 0755 "$tmp/kind" "$BIN/kind"
fi

if ! command -v kubectl >/dev/null || ! kubectl version --client 2>/dev/null | grep -q "$KUBECTL_VERSION"; then
  base="https://dl.k8s.io/release/$KUBECTL_VERSION/bin/linux/amd64"
  curl -fsSL -o "$tmp/kubectl" "$base/kubectl"
  expected=$(curl -fsSL "$base/kubectl.sha256")
  echo "$expected  $tmp/kubectl" | sha256sum -c -
  install -m 0755 "$tmp/kubectl" "$BIN/kubectl"
fi

case ":$PATH:" in
  *":$BIN:"*) ;;
  *) echo "export PATH=\"\$HOME/.local/bin:\$PATH\"" >> "$HOME/.bashrc"
     echo "added ~/.local/bin to PATH in ~/.bashrc (open a new terminal, or run: export PATH=\"\$HOME/.local/bin:\$PATH\")" ;;
esac
"$BIN/kind" version
"$BIN/kubectl" version --client

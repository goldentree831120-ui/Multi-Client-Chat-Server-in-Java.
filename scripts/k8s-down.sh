#!/usr/bin/env bash
# Deletes the local kind cluster "chat" (and with it everything deployed in it).
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
kind delete cluster --name chat

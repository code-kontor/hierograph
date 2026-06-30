#!/usr/bin/env bash
#
# setup-m2-settings.sh
#
# Creates (OVERWRITES!) ~/.m2/settings.xml from settings.xml.template,
# resolving the Proton Pass {{ ... }} secret references with pass-cli.
#
# The template carries the release settings from RELEASING.md:
#   * <server id="central"> — Central Publisher Portal token
#   * gpg-sign profile       — passphrase for the maven-gpg-plugin
#
# Usage:
#   ./setup-m2-settings.sh
#
# Template resolution:
#   Uses settings.xml.template.local if it exists (git-ignored — put your real
#   Proton Pass vault/item ids there), otherwise the committed
#   settings.xml.template (generic VAULT/ITEM placeholders). To create the local
#   copy:  cp settings.xml.template settings.xml.template.local  then edit.
#
# Requires: pass-cli (Proton Pass), an unlocked/authenticated session.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS_DIR="${HOME}/.m2"
SETTINGS_FILE="${SETTINGS_DIR}/settings.xml"

# Prefer a local, git-ignored template (with your real Proton Pass ids) over the
# committed one (which carries generic VAULT/ITEM placeholders).
TEMPLATE="${SCRIPT_DIR}/settings.xml.template"
LOCAL_TEMPLATE="${SCRIPT_DIR}/settings.xml.template.local"
if [[ -f "${LOCAL_TEMPLATE}" ]]; then
  TEMPLATE="${LOCAL_TEMPLATE}"
fi

# --- preflight ---------------------------------------------------------------
if ! command -v pass-cli >/dev/null 2>&1; then
  echo "error: pass-cli not found on PATH (Proton Pass CLI is required)." >&2
  exit 1
fi
if [[ ! -f "${TEMPLATE}" ]]; then
  echo "error: template not found: ${TEMPLATE}" >&2
  exit 1
fi
echo "using template: ${TEMPLATE}"

# --- resolve secrets into a temp file first ----------------------------------
# Inject into a temp file so a pass-cli failure can't leave a half-written
# settings.xml in place.
mkdir -p "${SETTINGS_DIR}"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

pass-cli inject --force --in-file "${TEMPLATE}" --out-file "${tmp}"

# --- validate the result -----------------------------------------------------
if command -v xmllint >/dev/null 2>&1; then
  if ! xmllint --noout "${tmp}"; then
    echo "error: injected settings.xml is not valid XML — leaving ~/.m2 untouched." >&2
    exit 1
  fi
fi

# --- back up an existing file, then install ----------------------------------
if [[ -f "${SETTINGS_FILE}" ]]; then
  cp -p "${SETTINGS_FILE}" "${SETTINGS_FILE}.bak"
  echo "backed up existing settings to ${SETTINGS_FILE}.bak"
fi

install -m 600 "${tmp}" "${SETTINGS_FILE}"
echo "wrote ${SETTINGS_FILE} (mode 600)"

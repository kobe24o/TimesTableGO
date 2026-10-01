#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --apk FILE --version-name NAME --version-code CODE --source-commit SHA --release-page-url URL --download-url URL --certificate SHA256 --private-key FILE --output FILE" >&2
  exit 2
}

apk=""; version_name=""; version_code=""; source_commit=""; release_page_url=""; download_url=""; certificate=""; private_key=""; output=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) apk="$2"; shift 2;;
    --version-name) version_name="$2"; shift 2;;
    --version-code) version_code="$2"; shift 2;;
    --source-commit) source_commit="$2"; shift 2;;
    --release-page-url) release_page_url="$2"; shift 2;;
    --download-url) download_url="$2"; shift 2;;
    --certificate) certificate="$2"; shift 2;;
    --private-key) private_key="$2"; shift 2;;
    --output) output="$2"; shift 2;;
    *) usage;;
  esac
done

[[ -f "$apk" && -f "$private_key" && -n "$version_name" && "$version_code" =~ ^[1-9][0-9]*$ ]] || usage
[[ "$release_page_url" == https://* && "$download_url" == https://* && "$certificate" =~ ^[0-9a-f]{64}$ ]] || usage

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
file_name="$(basename "$apk")"
size="$(stat -c%s "$apk")"
sha256="$(sha256sum "$apk" | awk '{print $1}')"
published_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -cn \
  --arg versionName "$version_name" \
  --argjson versionCode "$version_code" \
  --arg publishedAt "$published_at" \
  --arg sourceCommit "$source_commit" \
  --arg releasePageUrl "$release_page_url" \
  --arg packageName "com.example.multiplicationcoach" \
  --arg fileName "$file_name" \
  --argjson size "$size" \
  --arg sha256 "$sha256" \
  --arg signingCertificateSha256 "$certificate" \
  --arg downloadUrl "$download_url" \
  '{versionName:$versionName,versionCode:$versionCode,publishedAt:$publishedAt,sourceCommit:$sourceCommit,releasePageUrl:$releasePageUrl,android:{packageName:$packageName,fileName:$fileName,size:$size,sha256:$sha256,signingCertificateSha256:$signingCertificateSha256,urls:[$downloadUrl]}}' \
  > "$work_dir/payload.json"

openssl dgst -sha256 -sign "$private_key" -out "$work_dir/signature.bin" "$work_dir/payload.json"
jq -cn \
  --arg payload "$(base64 -w0 < "$work_dir/payload.json")" \
  --arg signature "$(base64 -w0 < "$work_dir/signature.bin")" \
  '{protocol:1,payload:$payload,signature:$signature}' > "$output"

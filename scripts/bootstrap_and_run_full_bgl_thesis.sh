#!/usr/bin/env bash
set -Eeuo pipefail

readonly BGL_ARCHIVE_URL='https://zenodo.org/records/8196385/files/BGL.zip?download=1'
readonly BGL_ARCHIVE_MD5='4452953c470f2d95fcb32d5f6e733f7a'
readonly REQUIRED_MODEL='qwen3.5:35b'
readonly MIN_FREE_DISK_GIB=60
readonly EXPECTED_RAM_GIB=32
readonly EXPORT_FULL_LOG_EVALUATIONS_DEFAULT=false

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CURRENT_STAGE="startup"
CURRENT_BATCH_DIR=""
RUNNER_CAPTURE=""
BOOTSTRAP_TEMP_DIR=""
BOOTSTRAP_LOG_STAGING=""
TEE_PID=""
RUNNER_STARTED_AT=""
BATCH_DIRECTORIES_BEFORE=""
BATCH_DIRECTORIES_AFTER=""

detect_supported_os() {
  local os_release="$1"
  if [[ ! -r "$os_release" ]]; then
    echo "ERROR: Cannot read OS metadata: $os_release" >&2
    return 1
  fi

  # /etc/os-release is the distribution-defined source for these shell-safe values.
  # shellcheck disable=SC1090
  source "$os_release"
  DETECTED_OS_ID="${ID:-unknown}"
  DETECTED_OS_VERSION="${VERSION_ID:-unknown}"
  DETECTED_OS_NAME="${PRETTY_NAME:-${NAME:-unknown}}"
  DETECTED_OS_CODENAME="${VERSION_CODENAME:-}"
  DETECTED_ARCH="$(uname -m)"

  if [[ "$DETECTED_OS_ID" != "ubuntu" || ( "$DETECTED_OS_VERSION" != "22.04" && "$DETECTED_OS_VERSION" != "24.04" ) ]]; then
    echo "ERROR: Unsupported operating system." >&2
    echo "Detected: $DETECTED_OS_NAME (ID=$DETECTED_OS_ID VERSION_ID=$DETECTED_OS_VERSION)" >&2
    echo "Supported: Ubuntu 22.04 LTS or Ubuntu 24.04 LTS, 64-bit Linux." >&2
    return 1
  fi
  if [[ "$DETECTED_ARCH" != "x86_64" && "$DETECTED_ARCH" != "aarch64" ]]; then
    echo "ERROR: Unsupported CPU architecture: $DETECTED_ARCH" >&2
    echo "Supported architectures: x86_64 and aarch64." >&2
    return 1
  fi

  case "$DETECTED_OS_VERSION" in
    22.04) MONGODB_UBUNTU_CODENAME="jammy" ;;
    24.04) MONGODB_UBUNTU_CODENAME="noble" ;;
  esac
}

validate_bootstrap_options() {
  EXPORT_FULL_LOG_EVALUATIONS_ENABLED="${EXPORT_FULL_LOG_EVALUATIONS:-$EXPORT_FULL_LOG_EVALUATIONS_DEFAULT}"
  [[ "$EXPORT_FULL_LOG_EVALUATIONS_ENABLED" == true || "$EXPORT_FULL_LOG_EVALUATIONS_ENABLED" == false ]] || {
    echo "ERROR: EXPORT_FULL_LOG_EVALUATIONS must be true or false." >&2
    return 1
  }
}

preserve_partial_log() {
  local candidate="${CURRENT_BATCH_DIR:-}"
  if [[ -z "$candidate" && -n "${RUNNER_CAPTURE:-}" && -f "$RUNNER_CAPTURE" ]]; then
    candidate="$(sed -n 's/^Result directory: //p' "$RUNNER_CAPTURE" | tail -n 1)"
  fi
  if [[ -n "$candidate" && -d "$candidate" && "$candidate" == "$REPO_ROOT/results/thesis/"* && -f "${BOOTSTRAP_LOG_STAGING:-}" ]]; then
    cp -f -- "$BOOTSTRAP_LOG_STAGING" "$candidate/bootstrap.log" || true
  fi
}

on_error() {
  local status=$?
  trap - ERR
  echo
  echo "ERROR: bootstrap stage failed: $CURRENT_STAGE" >&2
  echo "Command: $BASH_COMMAND" >&2
  echo "Exit status: $status" >&2
  [[ -n "${BOOTSTRAP_LOG_STAGING:-}" ]] && echo "Bootstrap log: $BOOTSTRAP_LOG_STAGING" >&2
  preserve_partial_log
  exit "$status"
}

cleanup() {
  if [[ -n "${BOOTSTRAP_TEMP_DIR:-}" && -d "$BOOTSTRAP_TEMP_DIR" ]]; then
    case "$(basename "$BOOTSTRAP_TEMP_DIR")" in
      llmloganalyzer-bootstrap.*) rm -rf -- "$BOOTSTRAP_TEMP_DIR" ;;
    esac
  fi
}

require_root_access() {
  local reason="$1"
  if (( EUID == 0 )); then
    ROOT_COMMAND=()
    return
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    echo "ERROR: $reason requires root privileges, but sudo is not installed." >&2
    return 1
  fi
  if ! sudo -v; then
    echo "ERROR: $reason requires working sudo privileges." >&2
    echo "Run from a root shell or authorize sudo before restarting the bootstrap." >&2
    return 1
  fi
  ROOT_COMMAND=(sudo)
}

apt_update_once() {
  if [[ "${APT_UPDATED:-false}" != true ]]; then
    "${ROOT_COMMAND[@]}" apt-get update
    APT_UPDATED=true
  fi
}

ensure_base_dependencies() {
  local packages=(
    git openjdk-17-jdk python3 python3-pip python3-venv curl wget unzip gnupg
    ca-certificates coreutils findutils tar gzip
  )
  local missing=()
  local package
  for package in "${packages[@]}"; do
    if ! dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q '^install ok installed$'; then
      missing+=("$package")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Missing base packages: ${missing[*]}"
    require_root_access "Base dependency installation"
    apt_update_once
    "${ROOT_COMMAND[@]}" env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
  else
    echo "All required base packages are already installed."
  fi

  local java_binary
  java_binary="$(dpkg -L openjdk-17-jdk-headless 2>/dev/null | awk '/\/bin\/java$/ {print; exit}')"
  [[ -x "$java_binary" ]] || { echo "ERROR: Java 17 binary was not installed correctly." >&2; return 1; }
  export JAVA_HOME="${java_binary%/bin/java}"
  export PATH="$JAVA_HOME/bin:$PATH"
  local java_major
  java_major="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
  [[ "$java_major" == "17" ]] || { echo "ERROR: Expected Java 17, detected major version $java_major." >&2; return 1; }

  java -version
  python3 --version
  git --version
}

collect_resource_information() {
  CPU_COUNT="$(getconf _NPROCESSORS_ONLN)"
  CPU_MODEL="$(awk -F: '/model name|Model/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}' /proc/cpuinfo)"
  CPU_MODEL="${CPU_MODEL:-unknown}"
  RAM_TOTAL_BYTES="$(( $(awk '/MemTotal:/ {print $2}' /proc/meminfo) * 1024 ))"
  RAM_AVAILABLE_BYTES="$(( $(awk '/MemAvailable:/ {print $2}' /proc/meminfo) * 1024 ))"
  read -r DISK_FILESYSTEM DISK_TOTAL_BYTES DISK_AVAILABLE_BYTES DISK_MOUNT < <(df -PB1 "$REPO_ROOT" | awk 'NR==2 {print $1, $2, $4, $6}')
  KERNEL_VERSION="$(uname -srvo)"

  GPU_INFO=""
  if command -v nvidia-smi >/dev/null 2>&1; then
    GPU_INFO+="NVIDIA:\n$(nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader 2>&1 || true)"
  fi
  if command -v rocminfo >/dev/null 2>&1; then
    GPU_INFO+="${GPU_INFO:+\n}AMD ROCm:\n$(rocminfo 2>/dev/null | awk '/Name:|Marketing Name:/ {print}' | head -n 20 || true)"
  elif command -v lspci >/dev/null 2>&1; then
    local amd_lines
    amd_lines="$(lspci 2>/dev/null | grep -Ei 'AMD|ATI' | grep -Ei 'VGA|Display|3D' || true)"
    [[ -n "$amd_lines" ]] && GPU_INFO+="${GPU_INFO:+\n}AMD PCI:\n$amd_lines"
  fi
  GPU_INFO="${GPU_INFO:-No supported GPU utility/device detected; GPU is optional.}"
  GPU_INFO="$(printf '%b' "$GPU_INFO")"

  echo "============================================================"
  echo " HOST RESOURCE PREFLIGHT"
  echo "============================================================"
  echo "OS: $DETECTED_OS_NAME"
  echo "Architecture: $DETECTED_ARCH"
  echo "Kernel: $KERNEL_VERSION"
  echo "CPU: $CPU_MODEL"
  echo "CPU count: $CPU_COUNT"
  echo "Total RAM: $(numfmt --to=iec-i --suffix=B "$RAM_TOTAL_BYTES")"
  echo "Available RAM: $(numfmt --to=iec-i --suffix=B "$RAM_AVAILABLE_BYTES")"
  echo "Filesystem: $DISK_FILESYSTEM mounted at $DISK_MOUNT"
  echo "Filesystem total: $(numfmt --to=iec-i --suffix=B "$DISK_TOTAL_BYTES")"
  echo "Filesystem free: $(numfmt --to=iec-i --suffix=B "$DISK_AVAILABLE_BYTES")"
  printf 'GPU information:\n%s\n' "$GPU_INFO"
  echo "Expected minimum RAM: ${EXPECTED_RAM_GIB} GiB"
  echo "Required free disk: ${MIN_FREE_DISK_GIB} GiB"
  echo "============================================================"

  local minimum_disk_bytes=$(( MIN_FREE_DISK_GIB * 1024 * 1024 * 1024 ))
  if (( DISK_AVAILABLE_BYTES < minimum_disk_bytes )); then
    echo "ERROR: Insufficient free disk space. At least ${MIN_FREE_DISK_GIB} GiB is required before model, dataset, MongoDB, and result creation." >&2
    return 1
  fi
  local expected_ram_bytes=$(( EXPECTED_RAM_GIB * 1024 * 1024 * 1024 ))
  if (( RAM_TOTAL_BYTES < expected_ram_bytes )); then
    echo "WARNING: Total RAM is below the documented ${EXPECTED_RAM_GIB} GiB expectation; the run may be very slow or fail." >&2
  fi
}

validate_git_checkout() {
  GIT_BRANCH="$(git -C "$REPO_ROOT" branch --show-current)"
  GIT_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  [[ -n "$GIT_COMMIT" ]] || { echo "ERROR: Git commit metadata is unavailable." >&2; return 1; }
  [[ "$GIT_BRANCH" == "Qwen3.5-35B" ]] || {
    echo "ERROR: Expected branch Qwen3.5-35B, detected ${GIT_BRANCH:-detached HEAD}." >&2
    return 1
  }
  if [[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
    WORKTREE_CLEAN=true
  else
    WORKTREE_CLEAN=false
  fi
  echo "Git branch: $GIT_BRANCH"
  echo "Git commit: $GIT_COMMIT"
  echo "Worktree clean: $WORKTREE_CLEAN"
}

report_privilege_capability() {
  if (( EUID == 0 )); then
    echo "Privilege mode: running as root."
  elif command -v sudo >/dev/null 2>&1 && sudo -n true; then
    echo "Privilege mode: non-interactive sudo is available."
  else
    echo "Privilege mode: package/service changes will fail unless all required components are already ready."
  fi
}

has_systemd() {
  command -v systemctl >/dev/null 2>&1 && [[ "$(ps -p 1 -o comm= 2>/dev/null | tr -d '[:space:]')" == "systemd" ]]
}

install_mongodb_if_needed() {
  local installed=false
  if command -v mongod >/dev/null 2>&1 && command -v mongosh >/dev/null 2>&1 \
      && mongod --version 2>/dev/null | grep -Eq 'db version v8\.'; then
    installed=true
  fi
  if [[ "$installed" != true ]]; then
    require_root_access "MongoDB Community Edition 8.0 installation"
    local keyring='/usr/share/keyrings/mongodb-server-8.0.gpg'
    local source_list='/etc/apt/sources.list.d/mongodb-org-8.0.list'
    local key_ascii="$BOOTSTRAP_TEMP_DIR/mongodb-server-8.0.asc"
    local key_binary="$BOOTSTRAP_TEMP_DIR/mongodb-server-8.0.gpg"
    curl -fsSL 'https://pgp.mongodb.com/server-8.0.asc' -o "$key_ascii"
    gpg --batch --yes --dearmor --output "$key_binary" "$key_ascii"
    "${ROOT_COMMAND[@]}" install -m 0644 "$key_binary" "$keyring"

    local repo_line="deb [ arch=amd64,arm64 signed-by=$keyring ] https://repo.mongodb.org/apt/ubuntu $MONGODB_UBUNTU_CODENAME/mongodb-org/8.0 multiverse"
    if [[ ! -f "$source_list" || "$(cat "$source_list")" != "$repo_line" ]]; then
      printf '%s\n' "$repo_line" | "${ROOT_COMMAND[@]}" tee "$source_list" >/dev/null
    fi
    APT_UPDATED=false
    apt_update_once
    "${ROOT_COMMAND[@]}" env DEBIAN_FRONTEND=noninteractive apt-get install -y mongodb-org
  else
    echo "MongoDB Community Edition 8.x and mongosh are already installed."
  fi

  if has_systemd; then
    if ! systemctl is-active --quiet mongod || ! systemctl is-enabled --quiet mongod; then
      require_root_access "Starting and enabling MongoDB"
      "${ROOT_COMMAND[@]}" systemctl enable --now mongod
    fi
  elif ! pgrep -x mongod >/dev/null 2>&1; then
    require_root_access "Starting MongoDB without systemd"
    "${ROOT_COMMAND[@]}" mongod --config /etc/mongod.conf --fork
  fi

  local attempt
  for attempt in {1..60}; do
    if mongosh --quiet --eval 'db.runCommand({ ping: 1 })' >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  mongosh --quiet --eval 'db.runCommand({ ping: 1 })'
  MONGODB_VERSION="$(mongod --version | awk '/db version/ {print $3; exit}')"
  echo "MongoDB version: $MONGODB_VERSION"
}

wait_for_ollama() {
  local attempt
  for attempt in {1..120}; do
    if curl -fsS 'http://127.0.0.1:11434/api/tags' >/dev/null 2>&1; then
      return
    fi
    sleep 2
  done
  echo "ERROR: Ollama did not become ready at http://127.0.0.1:11434/api/tags" >&2
  return 1
}

model_digest_from_api() {
  curl -fsS 'http://127.0.0.1:11434/api/tags' | python3 -c '
import json, sys
required = sys.argv[1]
for model in json.load(sys.stdin).get("models", []):
    if model.get("name") == required or model.get("model") == required:
        digest = model.get("digest")
        if digest:
            print(digest)
            raise SystemExit(0)
raise SystemExit(1)
' "$REQUIRED_MODEL"
}

install_ollama_and_model() {
  if ! command -v ollama >/dev/null 2>&1; then
    require_root_access "Ollama installation"
    local installer="$BOOTSTRAP_TEMP_DIR/ollama-install.sh"
    curl -fsSL 'https://ollama.com/install.sh' -o "$installer"
    sh "$installer"
  else
    echo "Ollama is already installed."
  fi

  if has_systemd; then
    if ! systemctl is-active --quiet ollama || ! systemctl is-enabled --quiet ollama; then
      require_root_access "Starting and enabling Ollama"
      "${ROOT_COMMAND[@]}" systemctl enable --now ollama
    fi
  elif ! curl -fsS 'http://127.0.0.1:11434/api/tags' >/dev/null 2>&1; then
    nohup ollama serve >"$BOOTSTRAP_TEMP_DIR/ollama-serve.log" 2>&1 &
  fi
  wait_for_ollama

  if ! OLLAMA_MODEL_DIGEST="$(model_digest_from_api)"; then
    echo "Model $REQUIRED_MODEL is missing; starting the required large local download."
    ollama pull "$REQUIRED_MODEL"
    wait_for_ollama
    OLLAMA_MODEL_DIGEST="$(model_digest_from_api)"
  else
    echo "Required model is already installed: $REQUIRED_MODEL"
  fi

  ollama list
  ollama show "$REQUIRED_MODEL"
  OLLAMA_VERSION="$(ollama --version 2>&1 | head -n 1)"
  echo "Ollama version: $OLLAMA_VERSION"
  echo "Resolved model digest: $OLLAMA_MODEL_DIGEST"
}

write_dataset_marker() {
  local marker="$1" dataset_sha="$2"
  python3 - "$marker" "$BGL_ARCHIVE_URL" "$BGL_ARCHIVE_MD5" "$dataset_sha" <<'PY'
import datetime, json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = {
    "sourceUrl": sys.argv[2],
    "archiveMd5": sys.argv[3],
    "datasetSha256": sys.argv[4],
    "verifiedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
}

dataset_marker_matches() {
  local marker="$1" dataset_sha="$2"
  python3 - "$marker" "$BGL_ARCHIVE_URL" "$BGL_ARCHIVE_MD5" "$dataset_sha" <<'PY'
import json, pathlib, sys
try:
    data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)
ok = (data.get("sourceUrl") == sys.argv[2]
      and data.get("archiveMd5") == sys.argv[3]
      and data.get("datasetSha256") == sys.argv[4])
raise SystemExit(0 if ok else 1)
PY
}

download_verified_bgl() {
  local archive="$BOOTSTRAP_TEMP_DIR/BGL.zip"
  local extracted="$BOOTSTRAP_TEMP_DIR/BGL.log"
  rm -f -- "$archive" "$extracted"
  curl --fail --location --retry 5 --retry-delay 5 --output "$archive" "$BGL_ARCHIVE_URL"
  if ! printf '%s  %s\n' "$BGL_ARCHIVE_MD5" "$archive" | md5sum --check - >&2; then
    rm -f -- "$archive"
    echo "ERROR: Downloaded BGL archive failed MD5 verification and was deleted." >&2
    return 1
  fi
  local members=()
  mapfile -t members < <(unzip -Z1 "$archive" | awk 'BEGIN{IGNORECASE=0} /(^|\/)BGL\.log$/ {print}')
  [[ ${#members[@]} -eq 1 ]] || {
    echo "ERROR: Expected exactly one original BGL.log in the verified archive; found ${#members[@]}." >&2
    return 1
  }
  unzip -p "$archive" "${members[0]}" > "$extracted"
  [[ -s "$extracted" ]] || { echo "ERROR: Extracted BGL.log is empty." >&2; return 1; }
  printf '%s\n' "$extracted"
}

ensure_bgl_dataset() {
  DATASET_PATH="$REPO_ROOT/data/BGL/BGL.log"
  local marker="$REPO_ROOT/data/BGL/.official_bgl_source.json"
  local existing_sha=""
  local verified_file=""

  if [[ -s "$DATASET_PATH" ]]; then
    existing_sha="$(sha256sum "$DATASET_PATH" | awk '{print $1}')"
    echo "Existing BGL dataset found; it will not be overwritten."
    echo "Existing dataset SHA-256: $existing_sha"
    echo "Existing dataset lines: $(wc -l < "$DATASET_PATH")"
    if [[ -f "$marker" ]] && dataset_marker_matches "$marker" "$existing_sha"; then
      echo "Existing dataset matches its verified Zenodo installation marker."
    else
      echo "No matching verified installation marker exists; downloading the official archive to verify the existing file."
      verified_file="$(download_verified_bgl)"
      local verified_sha
      verified_sha="$(sha256sum "$verified_file" | awk '{print $1}')"
      if [[ "$existing_sha" != "$verified_sha" ]]; then
        echo "ERROR: Existing data/BGL/BGL.log does not match the verified official Zenodo archive." >&2
        echo "Existing file was preserved. Move it aside explicitly before retrying." >&2
        return 1
      fi
      write_dataset_marker "$marker" "$existing_sha"
    fi
  else
    if [[ -e "$DATASET_PATH" ]]; then
      echo "Existing BGL path is empty and will be replaced by the verified official file."
    fi
    verified_file="$(download_verified_bgl)"
    mkdir -p "$(dirname "$DATASET_PATH")"
    install -m 0644 "$verified_file" "$DATASET_PATH"
    existing_sha="$(sha256sum "$DATASET_PATH" | awk '{print $1}')"
    write_dataset_marker "$marker" "$existing_sha"
  fi

  [[ -s "$DATASET_PATH" ]] || { echo "ERROR: BGL dataset installation failed." >&2; return 1; }
  DATASET_SHA256="$(sha256sum "$DATASET_PATH" | awk '{print $1}')"
  DATASET_WC_LINES="$(wc -l < "$DATASET_PATH")"
  echo "BGL path: $DATASET_PATH"
  echo "BGL wc -l: $DATASET_WC_LINES"
  echo "BGL SHA-256: $DATASET_SHA256"
}

configure_environment() {
  if [[ ! -f "$REPO_ROOT/.env" ]]; then
    cp "$REPO_ROOT/.env.example" "$REPO_ROOT/.env"
    echo "Created .env from .env.example."
  else
    echo "Existing .env preserved. Bootstrap exports the frozen official values for this process."
  fi

  export MODEL_NAME="$REQUIRED_MODEL"
  export MODEL_VERSION=AUTO OLLAMA_BASE_URL='http://127.0.0.1:11434'
  export TEMPERATURE=0 TOP_P=0.9 REPEAT_PENALTY=1.0 SEED=42 FORMAT=json THINKING=false
  export NUM_CTX=8192 NUM_PREDICT=160 BGL_TEMPLATE_CACHE=true
  export BGL_DATASET_PATH='data/BGL/BGL.log'
  export MONGODB_HYBRID_URI='mongodb://127.0.0.1:27017/hybrid'
  export MONGODB_PROMPT_ONLY_URI='mongodb://127.0.0.1:27017/prompt_only'
  export THESIS_RESULTS_DIR='results/thesis'
  if [[ -v BGL_MAX_RECORDS ]]; then
    echo "Ignoring BGL_MAX_RECORDS=${BGL_MAX_RECORDS}; this bootstrap only permits the official FULL_DATASET run."
  fi
  unset BGL_MAX_RECORDS
}

run_tests() {
  chmod +x "$REPO_ROOT/mvnw"
  (cd "$REPO_ROOT" && ./mvnw clean test)
}

run_scientific_experiment() {
  RUNNER_CAPTURE="$BOOTSTRAP_TEMP_DIR/scientific-runner.log"
  BATCH_DIRECTORIES_BEFORE="$BOOTSTRAP_TEMP_DIR/batches-before.txt"
  BATCH_DIRECTORIES_AFTER="$BOOTSTRAP_TEMP_DIR/batches-after.txt"
  find "$REPO_ROOT/results/thesis" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort > "$BATCH_DIRECTORIES_BEFORE"
  RUNNER_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  unset BGL_MAX_RECORDS
  echo "Delegating scientific orchestration to scripts/run_bgl_thesis_experiments.sh"
  if (cd "$REPO_ROOT" && ./scripts/run_bgl_thesis_experiments.sh) 2>&1 | tee "$RUNNER_CAPTURE"; then
    :
  else
    local runner_status=${PIPESTATUS[0]}
    echo "ERROR: Scientific runner failed with status $runner_status." >&2
    return "$runner_status"
  fi

  local batch_id
  batch_id="$(sed -n 's/^experimentBatchId: //p' "$RUNNER_CAPTURE" | tail -n 1)"
  [[ "$batch_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || { echo "ERROR: Could not identify the batch created by this invocation." >&2; return 1; }
  find "$REPO_ROOT/results/thesis" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort > "$BATCH_DIRECTORIES_AFTER"
  local new_batches=()
  mapfile -t new_batches < <(comm -13 "$BATCH_DIRECTORIES_BEFORE" "$BATCH_DIRECTORIES_AFTER")
  [[ ${#new_batches[@]} -eq 1 ]] || {
    echo "ERROR: Expected exactly one new batch directory from this invocation; found ${#new_batches[@]}." >&2
    return 1
  }
  [[ "${new_batches[0]}" == "$batch_id" ]] || {
    echo "ERROR: Runner-reported batch $batch_id differs from new directory ${new_batches[0]}." >&2
    return 1
  }
  CURRENT_BATCH_DIR="$REPO_ROOT/results/thesis/$batch_id"
  [[ -d "$CURRENT_BATCH_DIR" ]] || { echo "ERROR: Reported batch directory is missing: $CURRENT_BATCH_DIR" >&2; return 1; }
}

validate_completed_batch() {
  local validation="$BOOTSTRAP_TEMP_DIR/full-run-validation.json"
  python3 "$REPO_ROOT/scripts/validate_full_bgl_thesis_results.py" "$CURRENT_BATCH_DIR" \
    --expected-batch-id "$(basename "$CURRENT_BATCH_DIR")" --not-before "$RUNNER_STARTED_AT" \
    --expected-git-commit "$GIT_COMMIT" --expected-model-digest "$OLLAMA_MODEL_DIGEST" > "$validation"
  cat "$validation"
  mapfile -t VALIDATED_VALUES < <(python3 - "$validation" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("batchId", "datasetSha256", "datasetRecords", "hybridRunId", "promptOnlyRunId", "modelDigest"):
    print(data[key])
PY
  )
}

rewrite_method_checksums() {
  local directory="$1"
  (
    cd "$directory"
    find . -maxdepth 1 -type f ! -name artifact_sha256.txt -print0 \
      | sort -z | xargs -0 sha256sum > artifact_sha256.txt
  )
}

stream_exact_run_export() {
  local uri="$1" query_file="$2" destination="$3" expected_count="$4" label="$5"
  local partial="${destination}.partial"
  rm -f -- "$partial"
  mongoexport --uri="$uri" --collection=log_evaluations --queryFile="$query_file" \
    | gzip -c > "$partial"
  gzip -t "$partial"
  local exported_count
  exported_count="$(gzip -cd "$partial" | wc -l)"
  if [[ "$exported_count" != "$expected_count" ]]; then
    rm -f -- "$partial"
    echo "ERROR: $label full export contained $exported_count records; expected $expected_count." >&2
    return 1
  fi
  mv "$partial" "$destination"
  echo "$label exact-run export records: $exported_count"
}

export_full_evaluations_if_requested() {
  if [[ "$EXPORT_FULL_LOG_EVALUATIONS_ENABLED" != true ]]; then
    echo "Full line-level MongoDB export disabled (default)."
    return
  fi
  command -v mongoexport >/dev/null 2>&1 || { echo "ERROR: mongoexport is required for the optional full export." >&2; return 1; }

  local hybrid_id="${VALIDATED_VALUES[3]}" prompt_id="${VALIDATED_VALUES[4]}"
  local uuid_pattern='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  [[ "$hybrid_id" =~ $uuid_pattern && "$prompt_id" =~ $uuid_pattern ]] || {
    echo "ERROR: Validated run IDs are not safe UUID values." >&2
    return 1
  }
  local hybrid_query="$BOOTSTRAP_TEMP_DIR/hybrid-query.json"
  local prompt_query="$BOOTSTRAP_TEMP_DIR/prompt-query.json"
  printf '{"runId":"%s"}\n' "$hybrid_id" > "$hybrid_query"
  printf '{"runId":"%s"}\n' "$prompt_id" > "$prompt_query"
  stream_exact_run_export "$MONGODB_HYBRID_URI" "$hybrid_query" \
    "$CURRENT_BATCH_DIR/hybrid/log_evaluations_full.json.gz" "${VALIDATED_VALUES[2]}" "Hybrid"
  stream_exact_run_export "$MONGODB_PROMPT_ONLY_URI" "$prompt_query" \
    "$CURRENT_BATCH_DIR/prompt_only/log_evaluations_full.json.gz" "${VALIDATED_VALUES[2]}" "Prompt-only"
  rewrite_method_checksums "$CURRENT_BATCH_DIR/hybrid"
  rewrite_method_checksums "$CURRENT_BATCH_DIR/prompt_only"
  (cd "$CURRENT_BATCH_DIR/hybrid" && sha256sum --check artifact_sha256.txt)
  (cd "$CURRENT_BATCH_DIR/prompt_only" && sha256sum --check artifact_sha256.txt)
  echo "Exact-run full line-level exports completed."
}

write_execution_environment() {
  JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
  PYTHON_VERSION="$(python3 --version 2>&1)"
  export BOOTSTRAP_STARTED_AT DETECTED_OS_NAME DETECTED_OS_ID DETECTED_OS_VERSION DETECTED_OS_CODENAME
  export KERNEL_VERSION DETECTED_ARCH CPU_MODEL CPU_COUNT RAM_TOTAL_BYTES RAM_AVAILABLE_BYTES
  export DISK_FILESYSTEM DISK_TOTAL_BYTES DISK_AVAILABLE_BYTES DISK_MOUNT GPU_INFO
  export JAVA_VERSION PYTHON_VERSION MONGODB_VERSION OLLAMA_VERSION REQUIRED_MODEL OLLAMA_MODEL_DIGEST
  export GIT_COMMIT GIT_BRANCH WORKTREE_CLEAN DATASET_SHA256 DATASET_WC_LINES
  python3 - "$CURRENT_BATCH_DIR/execution_environment.json" <<'PY'
import datetime, json, os, pathlib, sys

def integer(name):
    return int(os.environ[name])

data = {
    "bootstrapStartedAtUtc": os.environ["BOOTSTRAP_STARTED_AT"],
    "reportCreatedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "os": {
        "name": os.environ["DETECTED_OS_NAME"], "id": os.environ["DETECTED_OS_ID"],
        "version": os.environ["DETECTED_OS_VERSION"], "codename": os.environ["DETECTED_OS_CODENAME"],
    },
    "kernel": os.environ["KERNEL_VERSION"],
    "architecture": os.environ["DETECTED_ARCH"],
    "cpu": {"model": os.environ["CPU_MODEL"], "count": integer("CPU_COUNT")},
    "memory": {"totalBytes": integer("RAM_TOTAL_BYTES"), "availableBytesAtPreflight": integer("RAM_AVAILABLE_BYTES")},
    "disk": {
        "filesystem": os.environ["DISK_FILESYSTEM"], "mount": os.environ["DISK_MOUNT"],
        "totalBytes": integer("DISK_TOTAL_BYTES"), "availableBytesAtPreflight": integer("DISK_AVAILABLE_BYTES"),
    },
    "gpu": os.environ["GPU_INFO"],
    "javaVersion": os.environ["JAVA_VERSION"],
    "pythonVersion": os.environ["PYTHON_VERSION"],
    "mongodbVersion": os.environ["MONGODB_VERSION"],
    "ollamaVersion": os.environ["OLLAMA_VERSION"],
    "modelTag": os.environ["REQUIRED_MODEL"],
    "modelDigest": os.environ["OLLAMA_MODEL_DIGEST"],
    "datasetSha256BeforeJavaPreflight": os.environ["DATASET_SHA256"],
    "datasetWcLines": integer("DATASET_WC_LINES"),
    "gitCommit": os.environ["GIT_COMMIT"],
    "gitBranch": os.environ["GIT_BRANCH"],
    "worktreeCleanBeforeRun": os.environ["WORKTREE_CLEAN"] == "true",
}
pathlib.Path(sys.argv[1]).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
}

close_and_copy_bootstrap_log() {
  echo "Packaging stage: scientific validation is complete; only the validated current batch will be archived."
  exec 1>&3 2>&4
  wait "$TEE_PID"
  cp -f -- "$BOOTSTRAP_LOG_STAGING" "$CURRENT_BATCH_DIR/bootstrap.log"
  rm -f -- "$BOOTSTRAP_LOG_STAGING"
}

package_current_batch() {
  local batch_id="${VALIDATED_VALUES[0]}"
  local results_root="$REPO_ROOT/results/thesis"
  [[ "$CURRENT_BATCH_DIR" == "$results_root/$batch_id" ]] || {
    echo "ERROR: Refusing to package a directory not identified as the current invocation batch." >&2
    return 1
  }
  [[ -f "$CURRENT_BATCH_DIR/bootstrap.log" && -f "$CURRENT_BATCH_DIR/execution_environment.json" ]] || {
    echo "ERROR: Bootstrap reproducibility files are missing from the current batch." >&2
    return 1
  }
  ARCHIVE_PATH="$results_root/LLMLogAnalyzer_FULL_BGL_${batch_id}.tar.gz"
  ARCHIVE_SHA_PATH="$ARCHIVE_PATH.sha256"
  [[ ! -e "$ARCHIVE_PATH" && ! -e "$ARCHIVE_SHA_PATH" ]] || {
    echo "ERROR: Refusing to overwrite an existing handoff archive: $ARCHIVE_PATH" >&2
    return 1
  }
  local temporary_archive="$ARCHIVE_PATH.partial"
  tar -C "$results_root" -czf "$temporary_archive" "$batch_id"
  mv "$temporary_archive" "$ARCHIVE_PATH"
  (cd "$results_root" && sha256sum "$(basename "$ARCHIVE_PATH")" > "$(basename "$ARCHIVE_SHA_PATH")")
  ARCHIVE_SHA256="$(awk '{print $1}' "$ARCHIVE_SHA_PATH")"
}

bootstrap_self_test() (
  local self_test_dir
  self_test_dir="$(mktemp -d "${TMPDIR:-/tmp}/llmloganalyzer-bootstrap-self-test.XXXXXX")"
  local jammy="$self_test_dir/jammy" noble="$self_test_dir/noble" unsupported="$self_test_dir/unsupported"
  trap 'rm -f -- "$jammy" "$noble" "$unsupported"; rmdir "$self_test_dir" 2>/dev/null || true' EXIT
  printf 'ID=ubuntu\nVERSION_ID="22.04"\nPRETTY_NAME="Ubuntu 22.04 LTS"\nVERSION_CODENAME=jammy\n' > "$jammy"
  printf 'ID=ubuntu\nVERSION_ID="24.04"\nPRETTY_NAME="Ubuntu 24.04 LTS"\nVERSION_CODENAME=noble\n' > "$noble"
  printf 'ID=debian\nVERSION_ID="12"\nPRETTY_NAME="Debian 12"\nVERSION_CODENAME=bookworm\n' > "$unsupported"
  detect_supported_os "$jammy"
  [[ "$MONGODB_UBUNTU_CODENAME" == jammy ]]
  detect_supported_os "$noble"
  [[ "$MONGODB_UBUNTU_CODENAME" == noble ]]
  if detect_supported_os "$unsupported" >/dev/null 2>&1; then
    echo "ERROR: unsupported OS self-test unexpectedly passed." >&2
    return 1
  fi
  [[ "$BGL_ARCHIVE_URL" == 'https://zenodo.org/records/8196385/files/BGL.zip?download=1' ]]
  [[ "$BGL_ARCHIVE_MD5" == '4452953c470f2d95fcb32d5f6e733f7a' ]]
  [[ "$REQUIRED_MODEL" == 'qwen3.5:35b' ]]
  [[ "$EXPORT_FULL_LOG_EVALUATIONS_DEFAULT" == false ]]
  (EXPORT_FULL_LOG_EVALUATIONS=true; validate_bootstrap_options)
  if (EXPORT_FULL_LOG_EVALUATIONS=unexpected; validate_bootstrap_options >/dev/null 2>&1); then
    echo "ERROR: invalid export option self-test unexpectedly passed." >&2
    return 1
  fi
  bash -n "$REPO_ROOT/scripts/run_bgl_thesis_experiments.sh"
  bash -n "$REPO_ROOT/scripts/bootstrap_and_run_full_bgl_thesis.sh"
  local python_command=python3
  if ! python3 --version >/dev/null 2>&1; then
    python_command=python
  fi
  "$python_command" "$REPO_ROOT/scripts/validate_full_bgl_thesis_results.py" --help >/dev/null
  echo "Bootstrap non-destructive self-test passed."
)

main() {
  cd "$REPO_ROOT"

  trap on_error ERR
  trap cleanup EXIT

  # Compatibility must be established before logs, repositories, or packages are changed.
  detect_supported_os /etc/os-release
  validate_bootstrap_options
  echo "Detected supported host: $DETECTED_OS_NAME ($DETECTED_ARCH)"

  mkdir -p "$REPO_ROOT/results/thesis"
  BOOTSTRAP_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  BOOTSTRAP_TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/llmloganalyzer-bootstrap.XXXXXX")"
  BOOTSTRAP_LOG_STAGING="$REPO_ROOT/results/thesis/bootstrap_${BOOTSTRAP_STARTED_AT//[:]/-}_$$.log"
  exec 3>&1 4>&2
  exec > >(tee -a "$BOOTSTRAP_LOG_STAGING" >&3) 2>&1
  TEE_PID=$!

  echo "Bootstrap started: $BOOTSTRAP_STARTED_AT"
  echo "Repository: $REPO_ROOT"
  report_privilege_capability

  CURRENT_STAGE="base system dependencies"
  ensure_base_dependencies
  CURRENT_STAGE="resource preflight"
  collect_resource_information
  CURRENT_STAGE="Git reproducibility check"
  validate_git_checkout
  CURRENT_STAGE="MongoDB installation and readiness"
  install_mongodb_if_needed
  CURRENT_STAGE="Ollama installation and qwen3.5:35b readiness"
  install_ollama_and_model
  CURRENT_STAGE="official BGL dataset installation and verification"
  ensure_bgl_dataset
  CURRENT_STAGE="project environment configuration"
  configure_environment
  CURRENT_STAGE="Maven test suite"
  run_tests
  CURRENT_STAGE="official FULL_DATASET scientific runner"
  run_scientific_experiment
  CURRENT_STAGE="full-run result validation"
  validate_completed_batch
  CURRENT_STAGE="optional exact-run MongoDB export"
  export_full_evaluations_if_requested
  CURRENT_STAGE="execution environment report"
  write_execution_environment
  CURRENT_STAGE="bootstrap log finalization"
  close_and_copy_bootstrap_log
  CURRENT_STAGE="exact current-batch packaging"
  package_current_batch

  echo "============================================================"
  echo " FULL BGL THESIS EXECUTION SUCCESSFUL"
  echo "============================================================"
  echo "Git commit: $GIT_COMMIT"
  echo "Dataset SHA-256: ${VALIDATED_VALUES[1]}"
  echo "Dataset records: ${VALIDATED_VALUES[2]}"
  echo "Evaluation scope: FULL_DATASET"
  echo "Coverage: 100%"
  echo
  echo "Hybrid:"
  echo "  runId: ${VALIDATED_VALUES[3]}"
  echo "  database: hybrid"
  echo "  evaluated records: ${VALIDATED_VALUES[2]}"
  echo
  echo "Prompt-only:"
  echo "  runId: ${VALIDATED_VALUES[4]}"
  echo "  database: prompt_only"
  echo "  evaluated records: ${VALIDATED_VALUES[2]}"
  echo
  echo "Results directory:"
  echo "  $CURRENT_BATCH_DIR/"
  echo
  echo "SEND THIS FILE TO MASOUD:"
  echo "  $ARCHIVE_PATH"
  echo
  echo "SHA-256:"
  echo "  $ARCHIVE_SHA256"
  echo "Checksum file:"
  echo "  $ARCHIVE_SHA_PATH"
  echo "============================================================"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  if [[ "${1:-}" == "--self-test" ]]; then
    bootstrap_self_test
  else
    main "$@"
  fi
fi

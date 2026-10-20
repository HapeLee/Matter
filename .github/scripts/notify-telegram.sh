#!/usr/bin/env bash
# 把发布结果推送到 Telegram 频道/群。
# 用法: notify-telegram.sh <version> <code> <is_release> <notes-file> <apk> <release-url>
# 依赖环境变量 TG_BOT_TOKEN / TG_CHAT_ID，以及 runner 预装的 curl、jq。
set -euo pipefail

version=${1:?version is required}
code=${2:?code is required}
is_release=${3:?is_release is required}
notes_file=${4:?notes file is required}
apk=${5:?apk path is required}
release_url=${6:?release url is required}

token=${TG_BOT_TOKEN:?TG_BOT_TOKEN is required}
chat=${TG_CHAT_ID:?TG_CHAT_ID is required}

# Bot API 云端 sendDocument 单文件上限 50 MB
max_upload=$((50 * 1024 * 1024))
message_limit=4096
caption_limit=1024

api() {
    local method=$1
    shift
    local response
    response=$(curl -sS --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 600 \
        --form-string "chat_id=$chat" "$@" "https://api.telegram.org/bot$token/$method")
    if [[ "$(jq -r '.ok // false' <<<"$response")" != "true" ]]; then
        echo "::error::Telegram $method failed: $response"
        return 1
    fi
}

truncate_text() {
    local text=$1
    local limit=$2
    if (( ${#text} <= limit )); then
        printf '%s' "$text"
    else
        printf '%s...' "${text:0:$((limit - 3))}"
    fi
}

if [[ "$is_release" == "true" ]]; then
    title="Matter $version"
    label="Matter $version"
else
    title="Matter $version (预发布)"
    label="预发布 Matter $version"
fi

# Telegram 不渲染 Markdown 以外的 GitHub 语法，这里退化成纯文本，避免解析失败
plain_notes=$(sed -E 's/^#{1,6}[[:space:]]+//; s/\*\*//g' "$notes_file")

body=$(truncate_text "$plain_notes

完整更新日志: $release_url" $((message_limit - ${#title} - 2)))
api sendMessage --form-string "text=$title

$body" --form-string "disable_web_page_preview=true"
echo "Telegram: 已发送发布说明"

if [[ ! -f "$apk" ]]; then
    echo "::error::APK not found: $apk"
    exit 1
fi

size=$(stat -c%s "$apk")
name=$(basename "$apk")
caption=$(truncate_text "$label (build $code)

完整更新日志: $release_url" "$caption_limit")

if (( size > max_upload )); then
    human=$((size / 1024 / 1024))
    api sendMessage --form-string "text=APK 超过 Telegram Bot API 的 50 MB 上传限制（${human} MB），请从 Release 下载：$release_url" \
        --form-string "disable_web_page_preview=true"
    echo "Telegram: APK 超限，已改为发送下载链接"
    exit 0
fi

api sendDocument --form-string "caption=$caption" -F "document=@$apk"
echo "Telegram: 已发送 $name（$((size / 1024 / 1024)) MB）"

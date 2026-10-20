#!/usr/bin/env bash
# 把发布结果推送到 Telegram（频道 / 群组，可同时多个）。
# 用法: notify-telegram.sh <version> <code> <is_release> <notes-file> <apk> <release-url>
# 依赖环境变量 TG_BOT_TOKEN / TG_CHAT_ID，以及 runner 预装的 curl、jq。
# TG_CHAT_ID 支持逗号分隔的多个目标，例如 "-1003675920085,-1003959048934"（频道 + 群组）。
set -euo pipefail

version=${1:?version is required}
code=${2:?code is required}
is_release=${3:?is_release is required}
notes_file=${4:?notes file is required}
apk=${5:?apk path is required}
release_url=${6:?release url is required}

token=${TG_BOT_TOKEN:?TG_BOT_TOKEN is required}
targets=${TG_CHAT_ID:?TG_CHAT_ID is required}

# Bot API 云端 sendDocument 单文件上限 50 MB
max_upload=$((50 * 1024 * 1024))
message_limit=4096
caption_limit=1024

chat=""

api() {
    local method=$1
    shift
    local response
    response=$(curl -sS --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 600 \
        --form-string "chat_id=$chat" "$@" "https://api.telegram.org/bot$token/$method")
    if [[ "$(jq -r '.ok // false' <<<"$response")" != "true" ]]; then
        echo "::error::Telegram $method failed for $chat: $response"
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

if [[ ! -f "$apk" ]]; then
    echo "::error::APK not found: $apk"
    exit 1
fi
size=$(stat -c%s "$apk")
name=$(basename "$apk")

# Telegram 不渲染 GitHub 的 Markdown 标题语法，这里退化成纯文本，避免解析失败
plain_notes=$(sed -E 's/^#{1,6}[[:space:]]+//; s/\*\*//g' "$notes_file")

message=$(truncate_text "$plain_notes

完整更新日志: $release_url" $((message_limit - ${#title} - 2)))
caption=$(truncate_text "$label (build $code)

完整更新日志: $release_url" "$caption_limit")

publish_to() {
    chat=$1
    # 注意：函数在 `if` 条件中调用时 set -e 不生效，必须显式判断每次 API 调用
    api sendMessage --form-string "text=$title

$message" --form-string "disable_web_page_preview=true" || return 1
    echo "Telegram: 已发送发布说明 -> $chat"

    if (( size > max_upload )); then
        local human=$((size / 1024 / 1024))
        api sendMessage \
            --form-string "text=APK 超过 Telegram Bot API 的 50 MB 上传限制（${human} MB），请从 Release 下载：$release_url" \
            --form-string "disable_web_page_preview=true" || return 1
        echo "Telegram: APK 超限，已改为发送下载链接 -> $chat"
        return 0
    fi

    api sendDocument --form-string "caption=$caption" -F "document=@$apk" || return 1
    echo "Telegram: 已发送 $name（$((size / 1024 / 1024)) MB）-> $chat"
}

failed=0
IFS=',' read -r -a chat_list <<< "$targets"
sent=0
for entry in "${chat_list[@]}"; do
    entry=$(printf '%s' "$entry" | tr -d '[:space:]')
    [[ -n "$entry" ]] || continue
    if publish_to "$entry"; then
        sent=$((sent + 1))
    else
        failed=$((failed + 1))
    fi
done

if (( sent == 0 )); then
    echo "::error::没有可用的 Telegram 目标（TG_CHAT_ID 为空或全部失败）"
    exit 1
fi
if (( failed > 0 )); then
    echo "::error::$failed 个 Telegram 目标推送失败"
    exit 1
fi
echo "Telegram: 已推送到 $sent 个目标"

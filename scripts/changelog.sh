#!/bin/sh
# update.md 变更日志工具
#
# 约定：文件顶部永远是一个 ## latest 段，开发中的改动都写在这里；
#       发版时用它把 ## latest 就地改名成 ## v<版本> 更新，并在顶部插入新的 ## latest。
#       段内可以用 ### 修复 / ### 更改 分组：段边界只认 ## 开头的行，
#       所以 ### 小标题不会把段落切断；判断「有没有内容」时标题不计入。
#
# 用法:
#   changelog.sh finalize <文件> <版本号>   归档 latest 段并插入新的空 latest
#   changelog.sh has      <文件> latest      latest 段是否有正文（有 → 退出码 0）
#   changelog.sh has      <文件> <版本号>    该版本段是否有正文（有 → 退出码 0）
#   changelog.sh extract  <文件> latest     输出 latest 段正文（不含标题）
#   changelog.sh extract  <文件> <版本号>    输出 ## v<版本> 更新 段正文（不含标题）
#   changelog.sh title    <文件>            输出第一个 ## 标题（用于校验）
#
# 只依赖 POSIX sh + awk，本地和 GitHub Actions 跑结果一致。

set -eu

die() {
    printf '%s\n' "$*" >&2
    exit 2
}

# 段标题里的版本号：## v1.5.0 更新 → v1.5.0；## v1.5.0更新 → v1.5.0；## latest → latest
# 不靠「按空格取第一段」：标题里版本号后面漏了空格（## v1.5.0更新）也能认出来，
# 否则 release notes 与 update.json 会静默变成空的。
ver_of() {
    awk '{
        if (match($0, /v?[0-9]+(\.[0-9]+)+([-.+][0-9A-Za-z.]+)*/))
            print substr($0, RSTART, RLENGTH)
        else {
            split($0, a, " ")
            print a[1]
        }
    }'
}

heading_key() {
    printf '%s' "$1" | sed -E 's/^##[[:space:]]*//' | ver_of
}

# 输出某个段落的正文（不含标题行）
extract_body() {
    file=$1
    want=$2
    awk -v want="$want" '
        /^## / {
            if (on) exit
            t = $0
            sub(/^##[ \t]*/, "", t)
            if (match(t, /v?[0-9]+(\.[0-9]+)+([-.+][0-9A-Za-z.]+)*/))
                h = substr(t, RSTART, RLENGTH)
            else {
                split(t, a, " ")
                h = a[1]
            }
            if (want == "latest") on = (h == "latest")
            else on = (h == "v" want || h == want)
            next
        }
        on { print }
    ' "$file"
}

# 正文里是否有实际内容
# 只认真正的条目：空行、HTML 注释、以及 ## / ### 标题都跳过。
# 段内允许用「### 修复」「### 更改」分组，只写了个标题没写条目仍然算空段 ——
# 否则 latest 里放两个光秃秃的小标题也能发版，「latest 为空就失败」的保护就废了。
body_has_text() {
    printf '%s\n' "$1" \
        | grep -v '^[[:space:]]*<!--' \
        | grep -v '^[[:space:]]*#' \
        | grep -q '[^[:space:]]'
}

cmd=${1:-}
[ -n "$cmd" ] || die "缺少子命令。用 changelog.sh 查看用法。"

case "$cmd" in
finalize)
    file=${2:-}
    ver=${3:-}
    [ -n "$file" ] && [ -n "$ver" ] || die "finalize 需要 <文件> <版本号>"
    [ -f "$file" ] || die "文件不存在：$file"

    top=$(awk '/^## /{print; exit}' "$file")
    [ -n "$top" ] || die "$file 里没有 ## 标题"
    key=$(heading_key "$top")
    [ "$key" = "latest" ] || die "$file 顶部是「$top」，应为「## latest」—— 先手动把待发布的改动挪进 ## latest 段"

    # 已有同名段就不重复归档（重发同一版本时会出现）
    # 用 body_has_text 判断：只剩分组标题、没有条目的空段落不算「已归档」
    if body_has_text "$(extract_body "$file" "$ver")"; then
        die "$file 里已经存在 v$ver 的段落，不能再归档一次"
    fi

    # 文件顶部的 <!-- ... --> 说明块不属于任何段，必须留在最前面。
    # 直接照搬「在最顶上插 latest」会把注释块挤进 latest 段里，
    # 之后 has latest 会把注释正文当成有内容，空段保护就失效了。
    awk -v ver="$ver" '
        { buf[NR] = $0 }
        END {
            n = NR

            # 找顶部注释块的范围（允许前面有空行）
            cend = 0
            s = 1
            while (s <= n && buf[s] ~ /^[[:space:]]*$/) s++
            if (s <= n && buf[s] ~ /^[[:space:]]*<!--/) {
                for (i = s; i <= n; i++) if (buf[i] ~ /-->/) { cend = i; break }
            }

            for (i = 1; i <= cend; i++) print buf[i]
            if (cend > 0) print ""

            print "## latest"
            print ""

            # 注释块之后的第一个 ## 标题就是原来的 latest，就地改名
            first = 0
            for (i = cend + 1; i <= n; i++) if (buf[i] ~ /^## /) { first = i; break }
            start = cend + 1
            while (start <= n && buf[start] ~ /^[[:space:]]*$/) start++
            for (i = start; i <= n; i++) {
                if (i == first) print "## v" ver " 更新"
                else print buf[i]
            }
        }
    ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "$file: ## latest → ## v${ver} 更新，并插入新的 ## latest"
    ;;

has)
    file=${2:-}
    want=${3:-}
    [ -n "$file" ] && [ -n "$want" ] || die "has 需要 <文件> <latest|版本号>"
    [ -f "$file" ] || exit 1
    body=$(extract_body "$file" "$want") || exit 1
    body_has_text "$body"
    ;;

extract)
    file=${2:-}
    want=${3:-}
    [ -n "$file" ] && [ -n "$want" ] || die "extract 需要 <文件> <latest|版本号>"
    [ -f "$file" ] || die "文件不存在：$file"
    extract_body "$file" "$want"
    ;;

title)
    file=${2:-}
    [ -n "$file" ] || die "title 需要 <文件>"
    [ -f "$file" ] || die "文件不存在：$file"
    awk '/^## /{print; exit}' "$file"
    ;;

*)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac

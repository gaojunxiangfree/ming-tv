#!/usr/bin/env bash
#
# 茗影院 一键发版脚本
#
#   用法: ./release.sh <版本号> [选项]
#         ./release.sh 1.0.1                 # 完整发版: 改版本号 -> 编译 -> 提交推送 -> 建 Release -> 传 APK
#         ./release.sh 1.0.1 --draft         # Release 建为草稿(不公开, 便于先自测)
#         ./release.sh 1.0.1 --notes a.md    # 用 a.md 作为 Release 说明(默认自动取 commit 记录)
#         ./release.sh --no-bump             # 沿用 build.gradle 里的当前版本号, 只走发布流程
#         ./release.sh 1.0.1 --skip-build    # 复用已编译好的 APK, 不重新编译
#         ./release.sh 1.0.1 --no-clean      # 编译时不执行 clean(加快速度, 但可能打进旧代码)
#         ./release.sh 1.0.1 --dry-run       # 只做全部前置检查(工作区/版本号/tag/令牌权限), 不改任何东西
#
# 版本号规则: 主版本.次版本.修订号, versionCode = 主*10000 + 次*100 + 修订
#
# 分支约定(开发与生产隔离):
#   feature/*  功能开发 -> 合入 dev
#   dev        集成/自测 -> 验证通过后合入 main
#   main       生产/发布 -> 只有这个分支能发包, 本脚本默认只允许在 main 上执行
#   (如需临时放宽: RELEASE_BRANCH=<分支名> ./release.sh ...)
#
# 令牌(GitHub 写权限)按以下顺序查找:
#   1) 环境变量 GH_TOKEN / GITHUB_TOKEN
#   2) 仓库根目录的 .gh_token 文件(已加入 .gitignore), 例: echo 'github_pat_xxx' > .gh_token
#   生成地址: https://github.com/settings/personal-access-tokens
#   需先改权限: Repository permissions -> Contents -> Read and write
#
# 依赖: bash / git / curl / python3 / JDK17
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

GRADLE_FILE="app/build.gradle"
APK_DIR="app/build/outputs/apk/release"
API="https://api.github.com"
UPLOAD_API="https://uploads.github.com"
TOKEN_FILE="$ROOT/.gh_token"

if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYA=$'\033[36m'; C_OFF=$'\033[0m'
else
    C_RED=; C_GRN=; C_YEL=; C_CYA=; C_OFF=
fi

info() { printf '%s\n' "${C_CYA}==>${C_OFF} $*"; }
ok()   { printf '%s\n' "${C_GRN} OK${C_OFF} $*"; }
warn() { printf '%s\n' "${C_YEL}  !${C_OFF} $*" >&2; }
die()  { printf '%s\n' "${C_RED}ERR${C_OFF} $*" >&2; exit 1; }

usage() {
    awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"
}

# ---------------------------------------------------------------- 参数解析
VERSION=""
DRAFT=0
NO_BUMP=0
NO_CLEAN=0
SKIP_BUILD=0
DRY_RUN=0
NOTES_FILE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --draft)      DRAFT=1 ;;
        --no-bump)    NO_BUMP=1 ;;
        --no-clean)   NO_CLEAN=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        --dry-run)    DRY_RUN=1 ;;
        --notes)      NOTES_FILE="${2:-}"; shift ;;
        -h|--help)    usage; exit 0 ;;
        -*)           usage; die "未知参数: $1" ;;
        *)            VERSION="$1" ;;
    esac
    shift
done

[ -f "$GRADLE_FILE" ] || die "找不到 $GRADLE_FILE, 请在仓库根目录执行"

# ------------------------------------------------------- 读取 build.gradle
read_gradle_version() {
    CUR_CODE="$(grep -E '^def appVersionCode' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+' || true)"
    CUR_NAME="$(grep -E '^def appVersionName' "$GRADLE_FILE" | head -1 | sed -E "s/.*'([^']*)'.*/\1/" || true)"
    [ -n "$CUR_CODE" ] && [ -n "$CUR_NAME" ] || die "无法从 $GRADLE_FILE 解析当前版本号"
}
read_gradle_version

if [ "$NO_BUMP" = "1" ]; then
    [ -z "$VERSION" ] || die "--no-bump 与指定版本号不能同时使用"
    VERSION="$CUR_NAME"
else
    [ -n "$VERSION" ] || { usage; die "缺少版本号, 例: ./release.sh 1.0.1"; }
fi

case "$VERSION" in
    [0-9]*.[0-9]*.[0-9]*) ;;
    *) die "版本号格式错误: $VERSION (应为 主.次.修订, 如 1.0.1)" ;;
esac

V_MAJOR="$(printf '%s' "$VERSION" | cut -d. -f1)"
V_MINOR="$(printf '%s' "$VERSION" | cut -d. -f2)"
V_PATCH="$(printf '%s' "$VERSION" | cut -d. -f3)"
case "$V_MAJOR$V_MINOR$V_PATCH" in
    *[!0-9]*) die "版本号只能包含数字与点: $VERSION" ;;
esac
NEW_CODE=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))

TAG="v$VERSION"
APK_NAME="ming-tv-v$VERSION.apk"
APK_PATH="$APK_DIR/$APK_NAME"
TODAY="$(date '+%Y-%m-%d')"

printf '%s\n' "----------------------------------------"
printf '  版本号   : %s (versionCode %s)\n' "$VERSION" "$NEW_CODE"
printf '  当前版本 : %s (versionCode %s)\n' "$CUR_NAME" "$CUR_CODE"
printf '  Tag      : %s\n' "$TAG"
printf '  产物     : %s\n' "$APK_PATH"
[ "$DRAFT" = "1" ] && printf '  模式     : 草稿 Release\n'
printf '%s\n' "----------------------------------------"

# ---------------------------------------------------------------- 前置检查
info "检查工作区状态"
if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short
    die "工作区有未提交的改动, 请先 commit 或 stash"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" != "HEAD" ] || die "当前处于游离 HEAD 状态, 请切回分支"

# 生产隔离: 默认只允许在 main 上发版, 避免把 dev 上的半成品打成 Release
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"
if [ "$BRANCH" != "$RELEASE_BRANCH" ]; then
    die "发版只允许在 $RELEASE_BRANCH 分支执行(当前: $BRANCH).
    请先把改动合入 $RELEASE_BRANCH: git checkout $RELEASE_BRANCH && git merge --no-ff $BRANCH
    如确需在当前分支发版: RELEASE_BRANCH=$BRANCH ./release.sh ..."
fi

git fetch --tags --quiet origin || warn "git fetch --tags 失败, 继续尝试"

if [ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]; then
    die "远端已存在 tag $TAG, 换个版本号"
fi

info "解析仓库地址"
REMOTE_URL="$(git remote get-url origin)"
SLUG="$REMOTE_URL"
SLUG="${SLUG%.git}"
SLUG="${SLUG#*://}"
SLUG="${SLUG#*@}"
case "$SLUG" in
    */*) SLUG="${SLUG#*/}" ;;
    *:*) SLUG="${SLUG#*:}" ;;
esac
case "$SLUG" in
    */*) ;;
    *) die "无法从 origin 解析 owner/repo: $REMOTE_URL" ;;
esac
ok "仓库: $SLUG (分支 $BRANCH, 远端 $REMOTE_URL)"

info "查找 GitHub 令牌"
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$TOKEN" ] && [ -f "$TOKEN_FILE" ]; then
    TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
fi
[ -n "$TOKEN" ] || die "未找到令牌. 可执行: echo 'github_pat_xxx' > .gh_token  (详见 --help)"
TOKEN_SRC="本地文件 .gh_token"
[ -n "${GH_TOKEN:-}" ] && TOKEN_SRC="环境变量 GH_TOKEN"
[ -n "${GITHUB_TOKEN:-}" ] && TOKEN_SRC="环境变量 GITHUB_TOKEN"
ok "令牌来源: $TOKEN_SRC"

info "校验令牌与仓库写权限"
PERM="$(curl -sS --max-time 20 \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$API/repos/$SLUG" -o /tmp/rel_check.json -w '%{http_code}')"
[ "$PERM" = "200" ] || { sed -n '1,5p' /tmp/rel_check.json; die "访问仓库失败 (HTTP $PERM), 检查令牌的有效期与仓库授权范围"; }
python3 - <<'PY'
import json, sys
d = json.load(open('/tmp/rel_check.json'))
if not d.get('permissions', {}).get('push'):
    sys.exit('令牌缺少写权限: 需将 Contents 设为 Read and write')
print('仓库:', d['full_name'], '| 私有:', d['private'])
PY
ok "令牌可用"

if [ "$DRY_RUN" = "1" ]; then
    GRADLE_TASKS_DESC="clean :app:assembleRelease"
    [ "$NO_CLEAN" = "1" ] && GRADLE_TASKS_DESC=":app:assembleRelease"
    printf '\n%s\n' "${C_GRN}预检全部通过 (--dry-run), 未做任何改动${C_OFF}"
    printf '  将写入    : %s -> versionCode %s / versionName %s\n' "$GRADLE_FILE" "$NEW_CODE" "$VERSION"
    printf '  将编译    : ./gradlew %s\n' "$GRADLE_TASKS_DESC"
    printf '  将推送    : %s (分支 %s)\n' "$SLUG" "$BRANCH"
    printf '  将建      : %s%s\n' "$TAG" "$([ "$DRAFT" = "1" ] && printf ' (草稿)')"
    printf '  将上传    : %s\n' "$APK_PATH"
    exit 0
fi

# ---------------------------------------------------------------- 改版本号
if [ "$NO_BUMP" = "0" ]; then
    if [ "$CUR_NAME" = "$VERSION" ]; then
        warn "版本号与当前一致($VERSION), 跳过修改"
    else
        info "更新 $GRADLE_FILE -> $VERSION"
        perl -pi -e "s/^def appVersionCode = .*/def appVersionCode = $NEW_CODE/" "$GRADLE_FILE"
        perl -pi -e "s/^def appVersionName = '.*'/def appVersionName = '$VERSION'/" "$GRADLE_FILE"
        perl -pi -e "s|^(// 版本历史:)\n|\$1\n//  $VERSION  $TODAY\n|" "$GRADLE_FILE"
        BUMPED=1
        read_gradle_version
        [ "$CUR_NAME" = "$VERSION" ] || die "版本号写入失败, 请检查 $GRADLE_FILE"
    fi
fi

BUMPED="${BUMPED:-0}"
COMMITTED=0
cleanup() {
    if [ "$BUMPED" = "1" ] && [ "$COMMITTED" != "1" ]; then
        warn "发布中断, 回滚 $GRADLE_FILE 的版本号改动"
        git checkout -- "$GRADLE_FILE" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- 编译
if [ "$SKIP_BUILD" = "1" ]; then
    info "跳过编译(--skip-build)"
else
    if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
        if [ -x /usr/libexec/java_home ]; then
            JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
        fi
    fi
    [ -n "${JAVA_HOME:-}" ] || die "未找到 JDK17, 请设置 JAVA_HOME"
    export JAVA_HOME
    info "使用 JAVA_HOME=$JAVA_HOME"

    if [ "$NO_CLEAN" = "1" ]; then
        warn "不执行 clean, 若改过资源可能打进旧代码"
        GRADLE_TASKS=":app:assembleRelease"
    else
        GRADLE_TASKS="clean :app:assembleRelease"
    fi
    info "编译: ./gradlew $GRADLE_TASKS"
    ./gradlew $GRADLE_TASKS || die "编译失败, 已回滚版本号"
fi

[ -f "$APK_PATH" ] || die "找不到编译产物 $APK_PATH (版本号与 build.gradle 是否一致?)"
APK_SIZE="$(stat -f%z "$APK_PATH" 2>/dev/null || stat -c%s "$APK_PATH")"
APK_SHA="$(shasum -a 256 "$APK_PATH" | cut -d' ' -f1)"
ok "产物 $(basename "$APK_PATH")  $(awk -v s="$APK_SIZE" 'BEGIN{printf "%.2f MB", s/1048576}')"
printf '   sha256 %s\n' "$APK_SHA"

# ---------------------------------------------------------------- 提交 + 推送
if [ "$NO_BUMP" = "0" ] && [ "$BUMPED" = "1" ]; then
    info "提交版本号变更"
    git add "$GRADLE_FILE"
    git commit -q -m "chore(release): ${VERSION}"
    ok "已提交: chore(release): ${VERSION}"
fi

info "推送 $BRANCH 到 origin"
git push -q origin "$BRANCH" || die "推送失败"
COMMITTED=1
HEAD_SHA="$(git rev-parse HEAD)"
ok "已推送 $HEAD_SHA"

# ---------------------------------------------------------------- Release 说明
if [ -n "$NOTES_FILE" ]; then
    [ -f "$NOTES_FILE" ] || die "找不到说明文件: $NOTES_FILE"
    BODY_SRC="$(cat "$NOTES_FILE")"
    info "Release 说明取自 $NOTES_FILE"
else
    PREV_TAG="$(git tag --sort=-v:refname | grep -v "^$TAG\$" | head -1 || true)"
    if [ -n "$PREV_TAG" ]; then
        RANGE="$PREV_TAG..HEAD"
    else
        RANGE="HEAD"
    fi
    BODY_SRC="$(git log --no-merges --pretty=format:'- %s' "$RANGE" || true)"
    info "Release 说明自动取自提交记录 ($RANGE)"
fi

printf '%s\n' "$BODY_SRC" > /tmp/rel_body.txt
python3 - "$TAG" "$DRAFT" "$APK_NAME" "$APK_SHA" "$HEAD_SHA" <<'PY' > /tmp/rel_body.json
import json, sys
tag, draft, apk_name, sha, head_sha = sys.argv[1:6]
changes = open('/tmp/rel_body.txt', encoding='utf-8').read().strip() or '- 维护性更新'
body = f"""## 茗影院 {tag}

### 本次变更
{changes}

### 安装说明
- 支持 Android 5.0 (API 21) 及以上, 平板 / 手机 / 投影仪 / TV 均可
- 首次启动可在「换源」中切换视频源, 或在设置里扫码推送接口配置

### 文件校验
- `{apk_name}`  sha256: `{sha}`

### 已知说明
- 部分第三方源存在源侧故障(如解密库缺失、接口返回异常), 与播放器本身无关
- 建议在真机电视 / 投影仪上验证网盘、DLNA 等横屏功能
"""
print(json.dumps({
    "tag_name": tag,
    "target_commitish": head_sha,
    "name": f"茗影院 {tag}",
    "body": body,
    "draft": draft == "1",
    "prerelease": False,
}, ensure_ascii=False))
PY

# ---------------------------------------------------------------- 建 Release
info "创建 GitHub Release $TAG"
REL_HTTP="$(curl -sS --max-time 40 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json; charset=utf-8" \
    --data-binary @/tmp/rel_body.json \
    "$API/repos/$SLUG/releases" \
    -o /tmp/rel_resp.json -w '%{http_code}')"
[ "$REL_HTTP" = "201" ] || { sed -n '1,8p' /tmp/rel_resp.json; die "创建 Release 失败 (HTTP $REL_HTTP)"; }

REL_ID="$(python3 -c "import json;print(json.load(open('/tmp/rel_resp.json'))['id'])")"
REL_URL="$(python3 -c "import json;print(json.load(open('/tmp/rel_resp.json'))['html_url'])")"
ok "Release 已创建 (id $REL_ID)"
printf '   %s\n' "$REL_URL"

# ---------------------------------------------------------------- 传 APK
info "上传 $(basename "$APK_PATH")"
UP_HTTP="$(curl -sS --max-time 900 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK_PATH" \
    "$UPLOAD_API/repos/$SLUG/releases/$REL_ID/assets?name=$APK_NAME" \
    -o /tmp/up_resp.json -w '%{http_code}')"
[ "$UP_HTTP" = "201" ] || { sed -n '1,8p' /tmp/up_resp.json; die "上传 APK 失败 (HTTP $UP_HTTP), Release 已建但无资产, 可在网页补传"; }

DL_URL="$(python3 -c "import json;print(json.load(open('/tmp/up_resp.json'))['browser_download_url'])")"
UP_SIZE="$(python3 -c "import json;print(json.load(open('/tmp/up_resp.json'))['size'])")"
ok "上传完成 $(awk -v s="$UP_SIZE" 'BEGIN{printf "%.2f MB", s/1048576}')"

git fetch --tags --quiet origin 2>/dev/null || true

printf '\n%s\n' "========================================"
printf '%s\n' "  ${C_GRN}发版完成${C_OFF}"
printf '  版本     : %s\n' "$VERSION"
printf '  提交     : %s\n' "$HEAD_SHA"
printf '  Release  : %s\n' "$REL_URL"
printf '  下载直链 : %s\n' "$DL_URL"
printf '  sha256   : %s\n' "$APK_SHA"
[ "$DRAFT" = "1" ] && printf '  %s\n' "注意: 当前为草稿 Release, 需在网页点 Publish release 才对外可见"
printf '%s\n' "========================================"

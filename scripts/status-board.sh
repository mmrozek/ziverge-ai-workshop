#!/usr/bin/env bash
# snap live status board — runs in a dedicated herdr pane (split DOWN from main).
#   ./scripts/status-board.sh          # event-driven: re-renders when a source file
#                                      # changes (fswatch on tasks/ + .git refs,
#                                      # 0.3s debounce; mtime-poll fallback), and
#                                      # notifies on task status transitions
#   ./scripts/status-board.sh --once   # single render (for testing)
#
# Height-aware: fits the pane (tput lines), prioritizing blocked > running > review.
# 256-color palette with basic-ANSI fallback (or BOARD_NO_COLOR=1).
#
# Data sources:
#   tasks/TASKS.md          durable board: | Task | Phase | Title | SP | Status | Depends | Commit |
#   tasks/CURRENT.md        ephemeral one-liner: what the orchestrator is doing (gitignored)
#   tasks/AGENTS-STATUS.md  ephemeral, one line per active in-process subagent (gitignored):
#                             name | role | doing | since
#   herdr agent list        pane-hosted agents (merged into the agents section)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BOARD_MD="$ROOT/tasks/TASKS.md"
CURRENT="$ROOT/tasks/CURRENT.md"
AGENTS_MD="$ROOT/tasks/AGENTS-STATUS.md"

# ---- palette -----------------------------------------------------------------
B=$'\033[1m'; X=$'\033[0m'
if [ "${BOARD_NO_COLOR:-}" != 1 ] && [ "$(tput colors 2>/dev/null || echo 8)" -ge 256 ]; then
  ACC=$'\033[38;5;75m'    # accent (soft blue) — title, phase labels
  TXT=$'\033[38;5;252m'   # primary text
  G=$'\033[38;5;114m'     # done (soft green)
  C=$'\033[38;5;80m'      # running (cyan)
  Y=$'\033[38;5;179m'     # review (amber)
  R=$'\033[38;5;203m'     # blocked (soft red)
  D=$'\033[38;5;242m'     # dim gray
  HL=$'\033[38;5;109m'    # commit hashes (steel blue)
  BE=$'\033[38;5;238m'    # progress bar empty
else
  ACC=$'\033[36m'; TXT=""
  G=$'\033[32m'; C=$'\033[36m'; Y=$'\033[33m'; R=$'\033[31m'
  D=$'\033[2m'; HL=$'\033[33m'; BE=$'\033[2m'
fi

snapshot() {
  [ -f "$BOARD_MD" ] || return 0
  awk -F'|' '/^\|[[:space:]]*T[0-9]+/ {
    t=$2; s=$6; gsub(/[[:space:]]/,"",t); gsub(/[[:space:]]/,"",s)
    printf "%s:%s\n", t, s
  }' "$BOARD_MD"
}

notify_changes() { # $1 = prev snapshot, $2 = new snapshot
  local changes="" line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    case "$1" in
      *"$line"*) ;;
      *) changes="${changes:+$changes, }${line/:/ → }" ;;
    esac
  done <<< "$2"
  [ -z "$changes" ] && return 0
  local sound=done
  case "$changes" in *blocked*) sound=request ;; esac
  herdr notification show "snap: task update" --body "$changes" --sound "$sound" >/dev/null 2>&1 || true
}

agent_lines() { # merged subagents (file) + herdr agents, one formatted row per line
  local aname arole adoing asince astat apane sc
  if [ -s "$AGENTS_MD" ]; then
    while IFS='|' read -r aname arole adoing asince; do
      aname="$(echo "$aname" | xargs)"; [ -z "$aname" ] && continue
      printf '  %s◉%s %s%-14s%s %s%-11s%s %s%.52s%s\n' \
        "$C" "$X" "$B$TXT" "$aname" "$X" "$D" "$(echo "$arole" | xargs)" "$X" \
        "$TXT" "$(echo "$adoing" | xargs)" "$X"
    done < "$AGENTS_MD"
  fi
  herdr agent list 2>/dev/null | jq -r '.result.agents[]? | [.agent, .agent_status, .pane_id] | @tsv' 2>/dev/null |
  while IFS=$'\t' read -r aname astat apane; do
    case "$astat" in
      working) sc="$C" ;; blocked) sc="$R" ;; idle|done) sc="$G" ;; *) sc="$D" ;;
    esac
    printf '  %s●%s %s%-14s%s %s%-11s%s %spane %s%s\n' \
      "$sc" "$X" "$B$TXT" "$aname" "$X" "$sc" "$astat" "$X" "$D" "$apane" "$X"
  done
}

render() {
  local H; H=$(tput lines 2>/dev/null || echo 24)

  # title bar: accent block + name + clock │ NOW
  printf '%s▍%ssnap%s %s%s%s %s│%s %s' "$ACC" "$B$TXT" "$X" "$D" "$(date '+%H:%M:%S')" "$X" "$D" "$X" "$TXT"
  if [ -s "$CURRENT" ]; then head -c 160 "$CURRENT" | tr '\n' ' '; printf '%s\n' "$X"
  else printf '%sidle%s\n' "$D" "$X"; fi
  printf '\n'

  if [ ! -f "$BOARD_MD" ]; then
    printf ' %s(no task board yet — plan not approved)%s\n' "$D" "$X"
  else
    local NP; NP=$(awk -F'|' '/^\|[[:space:]]*T[0-9]+/ {p=$3; gsub(/[[:space:]]/,"",p); if(!(p in s)){s[p]=1;n++}} END{print n+0}' "$BOARD_MD")
    local CAP=$(( H - NP - 13 )); [ "$CAP" -lt 3 ] && CAP=3
    awk -F'|' -v G="$G" -v C="$C" -v Y="$Y" -v R="$R" -v D="$D" -v B="$B" -v X="$X" \
        -v ACC="$ACC" -v TXT="$TXT" -v BE="$BE" -v CAP="$CAP" '
      BEGIN {
        sym["done"]="✔";        col["done"]=G;         pri["done"]=9
        sym["in-progress"]="▶"; col["in-progress"]=C;  pri["in-progress"]=1
        sym["review"]="◆";      col["review"]=Y;       pri["review"]=2
        sym["blocked"]="✖";     col["blocked"]=R;      pri["blocked"]=0
        sym["todo"]="·";        col["todo"]=D;         pri["todo"]=3
        eighth[0]=""; eighth[1]="▏"; eighth[2]="▎"; eighth[3]="▍"; eighth[4]="▌"
        eighth[5]="▋"; eighth[6]="▊"; eighth[7]="▉"
        n=0; np=0; total=0
      }
      function trim(v) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", v); return v }
      function hasdep(i) { return (dep[i]!="" && dep[i]!="—" && dep[i]!="-") }
      function depstr(i,   k, m, parts, out, dcol) {
        if (!hasdep(i)) return ""
        m = split(dep[i], parts, /,[[:space:]]*/)
        out = D "⇠ " X
        for (k=1; k<=m; k++) {
          dcol = (parts[k] in st) ? col[st[parts[k]]] : D
          out = out dcol parts[k] X (k<m ? D "," X : "")
        }
        return out
      }
      /^\|[[:space:]]*T[0-9]+/ {
        id0=trim($2); ph0=trim($3); ti0=trim($4); s0=trim($6); dp0=trim($7)
        if (!(s0 in sym)) s0="todo"
        n++; id[n]=id0; ph[n]=ph0; ti[n]=ti0; s[n]=s0; dep[n]=dp0; st[id0]=s0
        if (!(ph0 in seen)) { seen[ph0]=1; order[++np]=ph0 }
        total++; cnt[s0]++; pcnt[ph0]++
        if (s0=="done") pdone[ph0]++
      }
      END {
        if (n==0) { printf " %s(board is empty)%s\n", D, X; exit }
        for (p=1; p<=np; p++) {
          ph0=order[p]
          printf " %s%sP%s%s ", B, ACC, ph0, X
          for (i=1; i<=n; i++) if (ph[i]==ph0)
            printf "%s[%s%s]%s", col[s[i]], id[i], sym[s[i]], X
          printf "  %s%d/%d%s\n", D, pdone[ph0]+0, pcnt[ph0], X
        }
        # detail rows by priority: blocked, running, review, todo-with-deps
        shown=0; more=0
        for (pr=0; pr<=3; pr++)
          for (i=1; i<=n; i++) {
            if (pri[s[i]]!=pr) continue
            if (s[i]=="todo" && !hasdep(i)) continue
            if (s[i]=="done") continue
            if (shown < CAP) {
              printf "  %s%s %s%s%s%s %s%-30s%s %s\n", col[s[i]], sym[s[i]], B, col[s[i]], id[i], X, TXT, substr(ti[i],1,30), X, depstr(i)
              shown++
            } else more++
          }
        if (more) printf "  %s… +%d more%s\n", D, more, X
        printf "\n"
        # fractional-block progress bar + percent
        w=26; d=cnt["done"]+0
        f8 = int(w * 8 * d / total); full = int(f8/8); part = f8%8
        printf " %s", G
        for (i=0;i<full;i++) printf "█"
        printf "%s", eighth[part]
        printf "%s%s", X, BE
        for (i=full+(part>0?1:0);i<w;i++) printf "░"
        printf "%s %s%s%d%%%s %s%d/%d%s", X, B, TXT, int(100*d/total), X, D, d, total, X
        if (cnt["in-progress"]) printf "  %s▶%d%s", C, cnt["in-progress"], X
        if (cnt["review"])      printf " %s◆%d%s", Y, cnt["review"], X
        if (cnt["blocked"])     printf " %s%s✖%d BLOCKED%s", B, R, cnt["blocked"], X
        printf "\n"
      }
    ' "$BOARD_MD"
  fi

  # agents (cap 4 rows)
  local alines acount
  alines="$(agent_lines)"
  if [ -n "$alines" ]; then
    acount=$(printf '%s\n' "$alines" | wc -l | xargs)
    printf '%s\n' "$alines" | head -4
    [ "$acount" -gt 4 ] && printf '  %s… +%d more agents%s\n' "$D" "$((acount-4))" "$X"
  fi

  git -C "$ROOT" log --format='%h %s' -2 2>/dev/null | while read -r hash subj; do
    printf ' %s⎇%s %s%s%s %s%.62s%s\n' "$ACC" "$X" "$HL" "$hash" "$X" "$D" "$subj" "$X"
  done
  [ "$H" -ge 20 ] && printf ' %s✔done ▶run ◆review ✖blocked ·todo ⇠deps ◉subagent ●herdr%s\n' "$D" "$X"
}

redraw() {
  printf '\033[H\033[2J'
  render
  NEW="$(snapshot)"
  if [ -n "$PREV" ] && [ "$NEW" != "$PREV" ]; then
    notify_changes "$PREV" "$NEW"
  fi
  PREV="$NEW"
}

sig() { # cheap change signature for the no-fswatch fallback
  { stat -f '%m %z %N' "$BOARD_MD" "$CURRENT" "$AGENTS_MD" "$ROOT/.git/HEAD"
    git -C "$ROOT" rev-parse HEAD; } 2>/dev/null
}

if [ "${1:-}" = "--once" ]; then
  render
elif command -v fswatch >/dev/null 2>&1; then
  PREV="$(snapshot)"
  redraw
  fswatch -o --latency 0.3 "$ROOT/tasks" "$ROOT/.git/HEAD" "$ROOT/.git/refs" 2>/dev/null |
  while read -r _; do redraw; done
else
  PREV="$(snapshot)"; SIG=""
  redraw
  while true; do
    NSIG="$(sig)"
    if [ "$NSIG" != "$SIG" ]; then SIG="$NSIG"; redraw; fi
    sleep 1
  done
fi

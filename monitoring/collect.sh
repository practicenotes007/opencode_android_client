#!/bin/bash
# Resource monitor for dual opencode serve setup
# Collects: timestamp, per-process RSS/VSZ/CPU, system memory, swap
# Output: CSV to LOGFILE

LOGFILE="/home/ubuntu/2026.opencode/adhoc_jobs/opencode_android_client/monitoring/resource_log.csv"
mkdir -p "$(dirname "$LOGFILE")"

TIMESTAMP=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
TIMESTAMP_EPOCH=$(date +%s)

# ── Helper: get process stats by grep pattern ──
get_proc_stats() {
    local pattern="$1"
    local pid=$(pgrep -f "$pattern" | head -1)
    if [ -z "$pid" ]; then
        echo "0,0,0,0"
        return
    fi

    # /proc/[pid]/stat: fields: 1=pid 2=comm 14=utime 15=stime 23=vsz 24=rss
    # rss is in pages (4KB), vsz in bytes
    # /proc/[pid]/statm: 1=size(pages) 2=resident(pages)
    local statm=$(cat /proc/$pid/statm 2>/dev/null)
    local stat=$(cat /proc/$pid/stat 2>/dev/null)

    if [ -z "$statm" ] || [ -z "$stat" ]; then
        echo "0,0,0,0"
        return
    fi

    local rss_pages=$(echo "$statm" | awk '{print $2}')
    local vsz_pages=$(echo "$statm" | awk '{print $1}')
    local rss_mb=$((rss_pages * 4 / 1024))
    local vsz_mb=$((vsz_pages * 4 / 1024))

    # CPU time: utime + stime in clock ticks (100Hz typical)
    local utime=$(echo "$stat" | awk '{print $14}')
    local stime=$(echo "$stat" | awk '{print $15}')
    local cpu_ticks=$((utime + stime))

    # Process uptime: /proc/pid/stat field 22 = starttime
    local starttime=$(echo "$stat" | awk '{print $22}')
    local uptime_sec=0
    if [ -n "$starttime" ]; then
        local clk_tck=$(getconf CLK_TCK 2>/dev/null || echo 100)
        local boot_time=$(awk '{print $1}' /proc/uptime 2>/dev/null | cut -d. -f1)
        local uptime_raw=$(cat /proc/uptime 2>/dev/null | awk '{print $1}' | cut -d. -f1)
        uptime_sec=$((uptime_raw - starttime / clk_tck))
        [ $uptime_sec -lt 0 ] && uptime_sec=0
    fi

    echo "$rss_mb,$vsz_mb,$cpu_ticks,$uptime_sec"
}

# ── System memory ──
MEM_TOTAL=$(awk '/^MemTotal/  {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_AVAIL=$(awk '/^MemAvailable/ {printf "%.0f", $2/1024}' /proc/meminfo)
MEM_FREE=$(awk '/^MemFree/   {printf "%.0f", $2/1024}' /proc/meminfo)
SWAP_TOTAL=$(awk '/^SwapTotal/ {printf "%.0f", $2/1024}' /proc/meminfo)
SWAP_FREE=$(awk '/^SwapFree/  {printf "%.0f", $2/1024}' /proc/meminfo)
SWAP_USED=$((SWAP_TOTAL - SWAP_FREE))
MEM_USED=$((MEM_TOTAL - MEM_AVAIL))

# ── Per-process stats ──
DEVINOC_STATS=$(get_proc_stats "opencode serve.*3080")
WXP_STATS=$(get_proc_stats "opencode serve.*3081")

# ── Load average ──
LOAD=$(awk '{print $1","$2","$3}' /proc/loadavg)

# ── Write CSV header if file is empty ──
if [ ! -s "$LOGFILE" ]; then
    echo "timestamp,epoch,devinoc_rss_mb,devinoc_vsz_mb,devinoc_cpu_ticks,devinoc_uptime_sec,wxp_rss_mb,wxp_vsz_mb,wxp_cpu_ticks,wxp_uptime_sec,mem_total_mb,mem_used_mb,mem_avail_mb,swap_total_mb,swap_used_mb,load_1m,load_5m,load_15m" > "$LOGFILE"
fi

# ── Write data row ──
echo "$TIMESTAMP,$TIMESTAMP_EPOCH,$DEVINOC_STATS,$WXP_STATS,$MEM_TOTAL,$MEM_USED,$MEM_AVAIL,$SWAP_TOTAL,$SWAP_USED,$LOAD" >> "$LOGFILE"

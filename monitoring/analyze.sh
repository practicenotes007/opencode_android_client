#!/bin/bash
# Analyze resource_log.csv and generate a summary report
# Usage: bash analyze.sh [csv_file]

CSV="${1:-/home/ubuntu/2026.opencode/adhoc_jobs/opencode_android_client/monitoring/resource_log.csv}"

if [ ! -f "$CSV" ]; then
    echo "ERROR: $CSV not found"
    exit 1
fi

LINES=$(tail -n +2 "$CSV" | wc -l)
if [ "$LINES" -lt 2 ]; then
    echo "Not enough data points yet ($LINES samples). Need at least 2."
    exit 0
fi

echo "============================================"
echo " OpenCode Dual-Serve Resource Assessment"
echo " Data points: $LINES"
echo " Period: $(head -2 "$CSV" | tail -1 | cut -d, -f1) → $(tail -1 "$CSV" | cut -d, -f1)"
echo "============================================"
echo ""

# ── Helper: compute stats from a CSV column ──
col_stats() {
    local col="$1"
    local label="$2"
    local unit="$3"
    tail -n +2 "$CSV" | awk -F, -v col="$col" -v label="$label" -v unit="$unit" '
    $col > 0 {
        values[++n] = $col
        sum += $col
    }
    END {
        if (n == 0) { printf "  %s: NO DATA\n", label; exit }
        asort(values)
        min = values[1]
        max = values[n]
        avg = int(sum / n)
        p95_idx = int(n * 0.95)
        if (p95_idx < 1) p95_idx = 1
        p95 = values[p95_idx]
        printf "  %s: min=%d%s  avg=%d%s  p95=%d%s  max=%d%s\n", label, min, unit, avg, unit, p95, unit, max, unit
    }'
}

# ── Per-process memory ──
echo "── devinoc serve (3080) ──"
col_stats 3 "RSS " "MB"
col_stats 4 "VSZ " "MB"
echo "  Uptime range: $(tail -n +2 "$CSV" | awk -F, '{if($6>0) print $6}' | sort -n | head -1)s → $(tail -n +2 "$CSV" | awk -F, '{if($6>0) print $6}' | sort -n | tail -1)s"
echo ""

echo "── wxp serve (3081) ──"
col_stats 7 "RSS " "MB"
col_stats 8 "VSZ " "MB"
echo "  Uptime range: $(tail -n +2 "$CSV" | awk -F, '{if($10>0) print $10}' | sort -n | head -1)s → $(tail -n +2 "$CSV" | awk -F, '{if($10>0) print $10}' | sort -n | tail -1)s"
echo ""

# ── Combined RSS ──
echo "── Combined RSS (both serves) ──"
tail -n +2 "$CSV" | awk -F, '{
    rss = $3 + $7
    if (rss > 0) { values[++n] = rss; sum += rss }
}
END {
    if (n == 0) { print "  NO DATA"; exit }
    asort(values)
    min = values[1]; max = values[n]; avg = int(sum/n)
    p95_idx = int(n * 0.95); if (p95_idx < 1) p95_idx = 1; p95 = values[p95_idx]
    printf "  Combined RSS: min=%dMB  avg=%dMB  p95=%dMB  max=%dMB\n", min, avg, p95, max
}'
echo ""

# ── System memory ──
echo "── System Memory ──"
col_stats 11 "Total   " "MB"
col_stats 12 "Used    " "MB"
col_stats 13 "Avail   " "MB"
echo ""

# ── Swap ──
echo "── Swap ──"
col_stats 15 "Used    " "MB"
echo ""

# ── Load ──
echo "── Load Average ──"
tail -n +2 "$CSV" | awk -F, -v col=16 '{if($col>0) print $col}' | sort -n | awk '
{ values[++n] = $1; sum += $1 }
END {
    if (n==0) { print "  Load 1m : NO DATA"; exit }
    min = values[1]; max = values[n]; avg = sum/n
    p95_idx = int(n * 0.95); if (p95_idx < 1) p95_idx = 1; p95 = values[p95_idx]
    printf "  Load 1m : min=%.1f  avg=%.1f  p95=%.1f  max=%.1f\n", min, avg, p95, max
}'
tail -n +2 "$CSV" | awk -F, -v col=17 '{if($col>0) print $col}' | sort -n | awk '
{ values[++n] = $1; sum += $1 }
END {
    if (n==0) { print "  Load 5m : NO DATA"; exit }
    min = values[1]; max = values[n]; avg = sum/n
    p95_idx = int(n * 0.95); if (p95_idx < 1) p95_idx = 1; p95 = values[p95_idx]
    printf "  Load 5m : min=%.1f  avg=%.1f  p95=%.1f  max=%.1f\n", min, avg, p95, max
}'
tail -n +2 "$CSV" | awk -F, -v col=18 '{if($col>0) print $col}' | sort -n | awk '
{ values[++n] = $1; sum += $1 }
END {
    if (n==0) { print "  Load 15m: NO DATA"; exit }
    min = values[1]; max = values[n]; avg = sum/n
    p95_idx = int(n * 0.95); if (p95_idx < 1) p95_idx = 1; p95 = values[p95_idx]
    printf "  Load 15m: min=%.1f  avg=%.1f  p95=%.1f  max=%.1f\n", min, avg, p95, max
}'
echo ""

# ── Memory growth trend (linear regression on devinoc RSS over time) ──
echo "── Memory Growth Trend (devinoc serve) ──"
tail -n +2 "$CSV" | awk -F, '$3 > 0 { first_epoch = (first_epoch ? first_epoch : $2); first_rss = (first_rss ? first_rss : $3); last_epoch = $2; last_rss = $3 }
END {
    if (!first_epoch || first_epoch == last_epoch) { print "  Not enough data for trend analysis"; exit }
    delta_sec = last_epoch - first_epoch
    delta_mb = last_rss - first_rss
    hours = delta_sec / 3600
    rate = (hours > 0) ? delta_mb / hours : 0
    printf "  Period: %.1fh  RSS change: %dMB (%.1f MB/h)\n", hours, delta_mb, rate
    if (delta_mb < 0) print "  Trend: DECREASING (server restarted)"
    else if (delta_mb == 0) print "  Trend: STABLE"
    else {
        print "  Trend: GROWING"
        printf "  Estimated OOM risk in: %.0fh (at current rate, with 500MB safety)\n", (delta_mb > 0 && rate > 0) ? (500 / rate) : 0
    }
}'
echo ""

# ── OOM events in journal ──
echo "── OOM Events ──"
oom_count=$(journalctl -k --no-pager 2>/dev/null | grep -ci "oom-kill.*opencode" || echo 0)
echo "  OOM kills of opencode processes: $oom_count"
echo ""

echo "============================================"
echo " Report generated: $(date)"
echo " Raw data: $CSV"
echo "============================================"

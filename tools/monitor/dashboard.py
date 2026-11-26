"""
A live dashboard of an Android phone while the app runs: CPU load and frequency per core, temperatures, GPU load, memory, battery power, the app's own CPU,
memory and busiest threads, and the app's log. It needs only Python and adb (no packages), and serves http://127.0.0.1:8765 on this computer.

    python tools/monitor/dashboard.py [--serial SERIAL] [--port 8765] [--interval 1]

One long-running "adb shell" prints a sample every interval, so that watching costs the phone little. Nothing on the phone is changed. Every sample is also
kept in memory (30 minutes) and can be saved from the page as CSV.
"""
import argparse
import collections
import csv
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PACKAGE = "io.github.lnrdo.mytidal"
HISTORY = 1800

# What the phone prints for each sample. @ marks a section.
LOOP = r'''
ZONES=""; NAMES=""
for z in /sys/class/thermal/thermal_zone*; do
  t=$(cat $z/type 2>/dev/null)
  case "$t" in cpu-*-usr|cpuss-*-usr|gpuss-*-usr|npu-usr|ddr-usr|skin*|xo-therm*|quiet-therm*) ZONES="$ZONES $z/temp"; NAMES="$NAMES $t";; esac
done
while true; do
  echo "@time $(date +%s.%N)"
  echo "@stat"; grep '^cpu' /proc/stat
  echo "@freq"; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null
  echo "@max"; cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null
  echo "@zonenames $NAMES"
  echo "@zones"; cat $ZONES 2>/dev/null
  echo "@hal"; dumpsys thermalservice | sed -n '/Current temperatures from HAL/,/Current cooling/p;/Thermal Status/p'
  echo "@gpu"; cat /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null
  echo "@mem"; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo
  echo "@battery"; dumpsys battery | grep -E 'level|voltage|temperature|powered'
  echo "@current"; cat /sys/class/power_supply/battery/current_now 2>/dev/null
  PID=$(pidof ''' + PACKAGE + r''' | cut -d' ' -f1)
  echo "@app $PID"
  if [ -n "$PID" ]; then
    grep -E '^(VmRSS|VmHWM|VmSwap|Threads):' /proc/$PID/status
    echo "@appstat"; cat /proc/$PID/stat
    echo "@threads"; cat /proc/$PID/task/*/stat 2>/dev/null
  fi
  echo "@end"
  sleep INTERVAL
done
'''


class Phone:
    def __init__(self, serial, interval):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.interval = interval
        self.samples = collections.deque(maxlen=HISTORY)
        self.log = collections.deque(maxlen=200)
        self.lock = threading.Lock()
        self.previous = {}
        self.connected = False
        self.info = {}

    def shell(self, command):
        return subprocess.run(self.base + ["shell", command], capture_output=True, text=True, errors="replace", timeout=20).stdout

    def describe(self):
        values = self.shell("getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.board.platform; getprop ro.build.version.release").split("\n")
        values += [""] * 4
        self.info = {"name": f"{values[0].strip()} {values[1].strip()}".strip(), "chip": values[2].strip(), "android": values[3].strip()}

    def watch(self):
        script = LOOP.replace("INTERVAL", str(self.interval))
        while True:
            try:
                self.describe()
                process = subprocess.Popen(self.base + ["shell", script], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
                block = []
                for line in process.stdout:
                    line = line.rstrip("\r\n")
                    if line == "@end":
                        sample = self.parse(block)
                        block = []
                        if sample:
                            with self.lock:
                                self.samples.append(sample)
                                self.connected = True
                    else:
                        block.append(line)
                process.wait()
            except Exception as error:  # the phone may drop off adb at any time
                print("watch:", error, file=sys.stderr)
            self.connected = False
            self.previous = {}
            time.sleep(2)

    def watch_log(self):
        interesting = re.compile(r"OpenTune|AndroidRuntime: (FATAL|java|\s+at io\.github)|FATAL EXCEPTION|lowmemorykiller|Reclaim 'io\.github|ANR in " + re.escape(PACKAGE))
        while True:
            try:
                subprocess.run(self.base + ["wait-for-device"], timeout=3600)
                process = subprocess.Popen(self.base + ["logcat", "-v", "time", "-T", "50"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
                for line in process.stdout:
                    if interesting.search(line):
                        with self.lock:
                            self.log.append(line.rstrip()[:300])
                process.wait()
            except Exception as error:
                print("log:", error, file=sys.stderr)
            time.sleep(2)

    def parse(self, lines):
        sections, name = {}, None
        for line in lines:
            if line.startswith("@"):
                name, _, rest = line[1:].partition(" ")
                sections[name] = {"arg": rest.strip(), "lines": []}
            elif name:
                sections[name]["lines"].append(line)
        if "time" not in sections:
            return None
        get = lambda key: sections.get(key, {}).get("lines", [])
        now = float(sections["time"]["arg"])
        before = self.previous
        current = {"time": now}
        sample = {"t": now}
        elapsed = now - before.get("time", now)

        # CPU load from the change of the counters since the last sample.
        cores, total, counters = [], None, {}
        for line in get("stat"):
            parts = line.split()
            values = list(map(int, parts[1:]))
            idle, all_ticks = values[3] + values[4], sum(values[:8])
            counters[parts[0]] = (idle, all_ticks)
            load = None
            if parts[0] in before.get("stat", {}):
                d_idle, d_all = idle - before["stat"][parts[0]][0], all_ticks - before["stat"][parts[0]][1]
                load = max(0.0, min(100.0, 100.0 * (1 - d_idle / d_all))) if d_all > 0 else None
            if parts[0] == "cpu":
                total = load
            else:
                cores.append({"id": int(parts[0][3:]), "load": load})
        current["stat"] = counters
        freqs = [int(v) / 1000 for v in get("freq") if v.isdigit()]
        maxima = [int(v) / 1000 for v in get("max") if v.isdigit()]
        for index, core in enumerate(cores):
            core["mhz"] = freqs[core["id"]] if core["id"] < len(freqs) else None
            core["maxMhz"] = maxima[core["id"]] if core["id"] < len(maxima) else None
        sample["cpu"] = total
        sample["cores"] = cores

        # Temperatures: the thermal zones of the chip, and what the system's thermal service names.
        names = sections.get("zonenames", {}).get("arg", "").split()
        zones = {}
        for zone, value in zip(names, get("zones")):
            try:
                degrees = int(value) / 1000 if abs(int(value)) > 200 else int(value) / 10
            except ValueError:
                continue
            if -20 < degrees < 150:
                zones[zone] = degrees
        hal = {}
        for line in get("hal"):
            found = re.search(r"mValue=(-?[\d.]+), mType=(\d+), mName=([^,]+)", line)
            if found:
                value, kind = float(found.group(1)), int(found.group(2))
                if kind in (0, 1, 2, 3, 9) and -20 < value < 150:
                    hal.setdefault(kind, []).append(value)
            status = re.search(r"Thermal Status: (\d)", line)
            if status:
                sample["throttling"] = int(status.group(1))
        def hottest(prefix):
            values = [v for k, v in zones.items() if k.startswith(prefix)]
            return max(values) if values else None
        sample["temps"] = {
            "cpu": max(hal[0]) if 0 in hal else hottest("cpu"),
            "gpu": max(hal[1]) if 1 in hal else hottest("gpu"),
            "npu": zones.get("npu-usr"),
            "skin": max(hal[3]) if 3 in hal else None,
            "battery": hal[2][-1] if 2 in hal else None,
        }

        gpu = get("gpu")
        if gpu:
            parts = gpu[0].split()
            if len(parts) == 2 and int(parts[1]) > 0:
                sample["gpu"] = 100.0 * int(parts[0]) / int(parts[1])
            else:
                sample["gpu"] = 0.0
            if len(gpu) > 1 and gpu[1].strip().isdigit():
                sample["gpuMhz"] = int(gpu[1]) / 1e6

        memory = {}
        for line in get("mem"):
            parts = line.split()
            memory[parts[0].rstrip(":")] = int(parts[1]) / 1024
        sample["memTotal"] = memory.get("MemTotal")
        sample["memAvailable"] = memory.get("MemAvailable")
        sample["swapUsed"] = memory.get("SwapTotal", 0) - memory.get("SwapFree", 0)

        battery = dict(line.strip().split(": ", 1) for line in get("battery") if ": " in line)
        sample["level"] = int(battery.get("level", 0))
        sample["charging"] = any(v == "true" for k, v in battery.items() if k.endswith("powered"))
        if sample["temps"]["battery"] is None and "temperature" in battery:
            sample["temps"]["battery"] = int(battery["temperature"]) / 10
        raw = get("current")
        if raw and re.match(r"^-?\d+$", raw[0].strip()):
            value = int(raw[0])
            milliamps = value / 1000 if abs(value) > 20000 else value
            # The sign differs between phones: shown as the size of the current that flows.
            sample["milliamps"] = abs(milliamps)
            volts = int(battery.get("voltage", 0)) / 1000
            if volts:
                sample["watts"] = abs(milliamps) * volts / 1000

        # The app.
        pid = sections.get("app", {}).get("arg", "")
        sample["pid"] = int(pid) if pid.isdigit() else None
        for line in get("app"):
            parts = line.split()
            if parts[0] == "Threads:":
                sample["appThreads"] = int(parts[1])
            elif parts[0].startswith("Vm"):
                sample["app" + parts[0][2:].rstrip(":")] = int(parts[1]) / 1024

        def ticks(line):
            head, _, tail = line.rpartition(")")
            fields = tail.split()
            ident, _, comm = head.partition(" (")
            return int(ident), comm, int(fields[11]) + int(fields[12]), int(fields[36]) if len(fields) > 36 else None

        if get("appstat"):
            _, _, used, _ = ticks(get("appstat")[0])
            current["app"] = (sample["pid"], used)
            old = before.get("app")
            if old and old[0] == sample["pid"] and elapsed > 0:
                # Clock ticks are hundredths of a second: percent of one core, so 800 is eight cores busy.
                sample["appCpu"] = max(0.0, (used - old[1]) / elapsed)
        threads, busiest = {}, []
        for line in get("threads"):
            try:
                ident, comm, used, core = ticks(line)
            except (ValueError, IndexError):
                continue
            threads[ident] = used
            old = before.get("threads", {}).get(ident)
            if old is not None and elapsed > 0 and used > old:
                busiest.append({"name": comm, "tid": ident, "cpu": (used - old) / elapsed, "core": core})
        current["threads"] = threads
        sample["busiest"] = sorted(busiest, key=lambda t: -t["cpu"])[:10]
        self.previous = current
        return sample

    def snapshot(self, since):
        with self.lock:
            return {"connected": self.connected, "info": self.info, "samples": [s for s in self.samples if s["t"] > since], "log": list(self.log)[-60:]}

    def csv(self):
        out = io.StringIO()
        writer = csv.writer(out)
        writer.writerow(["time", "cpu_percent", "gpu_percent", "temp_cpu", "temp_gpu", "temp_npu", "temp_skin", "temp_battery", "throttling", "mem_available_mb",
                         "app_cpu_percent_of_core", "app_rss_mb", "app_threads", "watts", "battery_level", "charging"])
        with self.lock:
            for s in self.samples:
                t = s["temps"]
                writer.writerow([time.strftime("%H:%M:%S", time.localtime(s["t"])), s.get("cpu"), s.get("gpu"), t["cpu"], t["gpu"], t["npu"], t["skin"], t["battery"],
                                 s.get("throttling"), s.get("memAvailable"), s.get("appCpu"), s.get("appRSS"), s.get("appThreads"), s.get("watts"), s["level"], s["charging"]])
        return out.getvalue()


def serve(phone, port):
    class Handler(BaseHTTPRequestHandler):
        def send(self, body, kind, extra=None):
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path == "/data":
                found = re.search(r"since=([\d.]+)", query)
                self.send(json.dumps(phone.snapshot(float(found.group(1)) if found else 0)).encode(), "application/json")
            elif path == "/csv":
                self.send(phone.csv().encode(), "text/csv", {"Content-Disposition": "attachment; filename=phone.csv"})
            elif path == "/":
                with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboard.html"), "rb") as f:
                    self.send(f.read(), "text/html; charset=utf-8")
            else:
                self.send_error(404)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"dashboard on http://127.0.0.1:{port}", flush=True)
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()
    phone = Phone(args.serial, args.interval)
    threading.Thread(target=phone.watch, daemon=True).start()
    threading.Thread(target=phone.watch_log, daemon=True).start()
    serve(phone, args.port)


if __name__ == "__main__":
    main()

# RadonEye Driver for Ignition 8.3

An Ignition 8.3 OPC UA device driver that reads a **RadonEye RD200** radon detector over
**Bluetooth LE**, using the gateway PC's own Bluetooth adapter. It handles both the current RD200
(version 2: serials FR:RU, FR:RE, FR:GI, FR:H…, FR:I…, FR:J…, FR:K…) and the original one (version
1, FR:R2…). The protocol is ported from [rd200v2](https://github.com/jdeath/rd200v2), the Home
Assistant integration.

Module id `com.arringtonautomation.ignition.radoneye`, vendor Arrington Automation, Apache-2.0.

**Requirements:**
- The gateway runs on **Windows 10 (1803 or later) or 11**, with Bluetooth turned on.
- The RD200 is within Bluetooth range of the PC, about 10 m with no heavy walls in between.

---

## How it works

Java has no Bluetooth LE API, so the module ships a small PowerShell script, `rd200.ps1`, which
uses the Bluetooth API built into Windows:
- The scan runs in Windows PowerShell 5.1.
- The read runs in a few lines of C#, which the script compiles once with the .NET Framework
  compiler that comes with Windows (`csc.exe`). The compiled DLL is cached in `%TEMP%\rd200-*`.
- Windows PowerShell can't subscribe to Bluetooth events itself, which is why the read uses C#.

Nothing needs installing.

Every read interval (10 minutes by default, which is how often the RD200 updates):

1. The driver runs `rd200.ps1`. The script connects, sends three queries and gets three short
   replies, then disconnects. This takes a few seconds.
2. The driver decodes the replies (radon now, 1-day and 1-month averages, peak, pulse counts,
   uptime, firmware) and updates the tags.

Nothing stays connected between readings, so the Ecosense phone app still works. The app can only
connect when the driver isn't mid-reading, and the driver can't read while the app is open.

The script is copied to `data\var\radoneye\rd200.ps1` when the device starts. You can run it by
hand to diagnose Bluetooth problems:

```
powershell -NoProfile -ExecutionPolicy Bypass -File rd200.ps1 -Scan
powershell -NoProfile -ExecutionPolicy Bypass -File rd200.ps1 -Address C4:7C:8D:12:34:56
```

## Install

```
gradlew.bat build --console=plain        # -> build\RadonEye-Driver.unsigned.modl
```

Install the module under **Platform → Modules**. Then go to **Connections → Devices → Create
Device → RadonEye RD200**:

| Setting | Default | |
|---|---|---|
| Bluetooth Address | blank | Blank = use the strongest RadonEye in range. Once it's found, copy the `Address` tag value here, so a neighbour's detector can never be picked |
| Bluetooth Timeout (s) | 15 | Per step: connect, each reply |
| Read Interval (min) | 10 | 1–1440 |
| Show pCi/L | on | Off = Bq/m³ |

## Tags

| Tag | |
|---|---|
| `Radon` | Radon now: the RD200's latest 10-minute value |
| `Radon1Day`, `Radon1Month` | Averages. The EPA's 4.0 pCi/L action level is about the long-term average |
| `RadonPeak` | Peak since the detector was reset |
| `Units` | `pCi/L` or `Bq/m3` |
| `PulsesNow`, `PulsesLast10Min` | Raw detector counts (version 2 only) |
| `UptimeMinutes`, `Uptime` | Time since power-on/reset (`3d 04:05`) |
| `Firmware`, `Hardware`, `ProtocolVersion`, `Name`, `Address` | Identification |
| `LastReading`, `LastReadOk`, `Status` | When the last reading was taken, and what happened on the latest attempt |
| `ReadNow` | Write true to read immediately |

`PulsesNow`, `PulsesLast10Min`, `Firmware` and `Hardware` exist only on version 2 detectors. For an
original RD200 (FR:R2…) they are left out of the tree once the first reading identifies the
model. If you created Ignition tags for them, delete those tags.

Values keep the time of the reading as their timestamp. After a failed attempt they stay at the
last reading with **Uncertain** quality, and the driver retries a minute later.

## Troubleshooting

| Status | Cause |
|---|---|
| `No RadonEye (FR:...) found` | Bluetooth off, the detector out of range, or the **Ecosense app connected**. The app holds the detector's only Bluetooth connection, so close it fully on every phone |
| `Service discovery failed` / `Timed out ... connect` | Same causes; also try moving the detector closer. Windows sometimes needs Bluetooth toggled off and on after sleep |
| `Run this with Windows PowerShell 5.1` | Something replaced powershell.exe; the driver calls `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe` |
| Works from a Command Prompt, fails from the gateway | The Ignition service runs as LocalSystem. That worked on the first install, but Bluetooth may be refused to services on some Windows builds. Set the **Ignition** service to log on as your user account (services.msc → Ignition → Log On) and restart it |
| `Requires a Windows gateway` | The gateway isn't on Windows. A Linux/BlueZ helper would be needed instead |

## Testing without a detector

```
javac -d out gateway/src/main/java/com/arringtonautomation/ignition/radoneye/rd200/*.java tools/RadonEyeSelfCheck.java
cp -r gateway/src/main/resources/* out/
java -cp out com.arringtonautomation.ignition.radoneye.rd200.RadonEyeSelfCheck tools
```

This runs 100 checks:
- The decoder against rd200v2's own parser: 12 generated version 1 and version 2 replies, through
  `tools/make_reference_vectors.py`.
- The helper-process handling against `tools/fake_helper.py`: errors, garbage output, a hung
  helper, a flood of stderr.
- The script install.
- The replies from a real RD200 version 1 (FR:R20), from the first reading on 21 Oct 2026.

`rd200.ps1` is syntax-checked with the PowerShell parser. It only runs on Windows.

## License

Apache-2.0, see `LICENSE`. The protocol is ported from rd200v2 (MIT), see `NOTICE`.

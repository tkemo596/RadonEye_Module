# CLAUDE.md — RadonEye Driver for Ignition 8.3

Project memory. `README.md` has the user-facing docs.

## What this is

An OPC UA device driver for a RadonEye RD200 over Bluetooth LE, using the Bluetooth adapter on the
gateway PC. The user's gateway runs **directly on a Windows PC with onboard Bluetooth**, not a VM.
Module id `com.arringtonautomation.ignition.radoneye`, device type id
`com.arringtonautomation.radoneye.RadonEyeDevice`. The protocol is ported from jdeath/rd200v2
(MIT), parser.py.

**Status (21 Oct 2026):** `rd200.ps1` works on the user's gateway PC. Run from a Command Prompt,
`-Scan` found the detector (an `FR:R20` serial, so a **version 1**
detector), and `-Address` returned real replies (now 1.02 / day 1.32 / month 1.05 / peak 3.08
pCi/L, uptime 482 d). Those replies are in `tools/fixtures/real_fr_r20.json`, with the serial and Bluetooth address replaced by placeholders. 100/100 offline
checks pass.

**Verified on the gateway (21 Oct 2026):** the module builds against the real SDK and runs inside
the Ignition service as LocalSystem; Bluetooth from the service works, so no Log On change was
needed. Values match the hand-run script, `ReadNow` triggers an immediate reading, and the version
2-only tags drop out for the version 1 detector.

## What we learned getting rd200.ps1 to run (Windows PowerShell 5.1)

Each of these was hit in turn on the real machine:
1. `Register-ObjectEvent` refuses WinRT events ("cannot subscribe to Windows RT events").
2. Calling `.Dispose()` on a WinRT object from PowerShell throws ("must not be a Windows Runtime
   type").
3. GATT objects taken out of WinRT collections arrive as bare `System.__ComObject`. Neither
   reflection (on the class or its `IGattCharacteristic` interface) nor a compiled cast can
   attach an event to them.
4. `Add-Type` cannot reference `.winmd` files, so the script calls `csc.exe` directly and loads
   the DLL it builds.
5. `System.Runtime.WindowsRuntime.dll` (the `AsTask`/`AsBuffer`/`ToArray` extensions) references
   the union `Windows.winmd`, which ships only with the SDK. So: no `AsTask`; poll
   `IAsyncInfo.Status` instead. Buffers go through `DataReader.FromBuffer` and
   `DataWriter.DetachBuffer`.
6. A polling wait on PowerShell's STA thread never sees completions, so service discovery timed
   out. The read now runs on an **MTA thread**.

As a result, **the whole read is C# (`Rd200.Gatt.Read`), compiled with references to the split
WinMetadata files (Foundation, Devices, Storage) plus the System.Runtime facades.** The scan stays
in PowerShell: the watcher is created with New-Object, so it is properly typed, and the
reflection-based `Rd200.EventSink` attaches to its `Received` event.

## Design

- **Bluetooth goes through `rd200.ps1`.** It uses WinRT through Windows PowerShell 5.1 (pwsh 7
  can't load WinRT types). That means no JNI, no native libraries, nothing to install.
  - The script is a pure transport: it prints JSON `{version, name, address, replies:{"50":hex,
    "40":hex, "51":hex}, errors}`. All decoding is in Java, where it can be tested.
  - The read is the compiled `Rd200.Gatt.Read` described above. It does an uncached service
    lookup, tries version 2 and then version 1, and waits for the RD200's notifications.
  - V1 is tried after V2. Replies are collected until 400 ms of quiet, in case Windows splits
    the 68-byte 0x40 reply.
- **One short process per reading,** with the BLE device disposed at the end, so Windows drops
  the link and the Ecosense app can connect between readings.
- **The script is extracted** to `data/var/radoneye/rd200.ps1` (CRLF) at startup, and only
  rewritten when it differs from the bundled copy.
- **Version 2-only tags** (PulsesNow, PulsesLast10Min, Firmware, Hardware) are removed from the
  tree after the first reading identifies a version 1 detector, because otherwise they sit at Bad
  quality forever. Before the first reading the tree has every tag. The user's detector is
  version 1.
- **Address blank = scan** and use the strongest `FR:*` advertiser. When the address wasn't
  configured, a failure clears it so the next attempt rescans.
- **Units:** version 2 reports Bq/m³ (u16), version 1 reports pCi/L (float). Values are stored as
  Bq and converted at ×/÷37 exactly. rd200v2 uses 0.027; the fixture comparison allows ±0.011.

## Risks to check on the real machine

1. Does WinRT BLE work from the Ignition **service** (LocalSystem, session 0)? If not, set the
   service to log on as the user. The README covers this.
2. `Register-ObjectEvent` on the WinRT `GattCharacteristic.ValueChanged` under PS 5.1. It's the
   standard approach, but unverified here.
3. The write type: the script uses WriteWithResponse when the characteristic supports it,
   otherwise WithoutResponse.

## Files

```
gateway/src/main/java/com/arringtonautomation/ignition/radoneye/
  RadonEyeModuleHook, RadonEyeDeviceExtensionPoint, RadonEyeDeviceConfig, RadonEyeDevice
  rd200/Rd200Reading (decoder), Rd200Helper (process runner + script install), Rd200Exception, Json
gateway/src/main/resources/com/arringtonautomation/ignition/radoneye/rd200.ps1
tools/RadonEyeSelfCheck.java, fake_helper.py, make_reference_vectors.py, fixtures/parser_reference.json
```

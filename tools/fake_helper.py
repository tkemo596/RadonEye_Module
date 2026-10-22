"""Stands in for rd200.ps1 in RadonEyeSelfCheck: python fake_helper.py <mode> [rd200.ps1 args...]"""
import json, sys, time
mode = sys.argv[1]
args = sys.argv[2:]
if mode == "ok":
    if "-Scan" in args:
        print(json.dumps({"mode": "scan", "devices": [
            {"address": "C4:7C:8D:00:00:01", "name": "FR:RU0001", "rssi": -80},
            {"address": "C4:7C:8D:00:00:02", "name": "FR:RU0002", "rssi": -55}]}))
    else:
        addr = args[args.index("-Address") + 1]
        print("WARNING: some PowerShell noise first")
        print(json.dumps({"mode": "read", "address": addr, "name": "FR:RU0002", "version": 2, "errors": [],
                          "replies": {"50": "500a5d002e07a0018b0f2349", "51": "510e00004023b9000000000000000000"}}))
elif mode == "error":
    print(json.dumps({"error": "No Bluetooth LE device at C4:7C:8D:00:00:09 (out of range, or Bluetooth is off)"}))
    sys.exit(1)
elif mode == "noreply":
    print(json.dumps({"mode": "read", "address": "x", "version": 2, "replies": {}, "errors": ["no reply to 50"]}))
elif mode == "garbage":
    print("this is not json")
    sys.stderr.write("Exception calling AsTask\n")
    sys.exit(3)
elif mode == "hang":
    time.sleep(60)
elif mode == "noisy":
    sys.stderr.write("x" * 2_000_000)
    print(json.dumps({"mode": "scan", "devices": []}))

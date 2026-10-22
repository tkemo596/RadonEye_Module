"""Runs jdeath/rd200v2's own parser (rd200_ble/parser.py) over generated RD200 replies and writes
what it decodes to tools/fixtures/parser_reference.json, so the Java decoder is checked against
the original rather than against my reading of it. bleak is replaced by a fake client.

    python tools/make_reference_vectors.py <rd200v2 checkout> tools/fixtures/parser_reference.json
"""
import asyncio, importlib.util, json, logging, random, struct, sys, types
from pathlib import Path

for name in ["bleak", "bleak.backends", "bleak.backends.device", "bleak.backends.service",
             "bleak_retry_connector", "async_interrupt"]:
    sys.modules[name] = types.ModuleType(name)
sys.modules["bleak"].BleakClient = object
sys.modules["bleak"].BleakError = Exception
sys.modules["bleak.backends.device"].BLEDevice = object
sys.modules["bleak.backends.service"].BleakGATTService = object
sys.modules["bleak_retry_connector"].establish_connection = None
sys.modules["bleak_retry_connector"].BleakClientWithServiceCache = object
sys.modules["async_interrupt"].interrupt = None

pkg_dir = Path(sys.argv[1]) / "custom_components" / "rd200_ble" / "rd200_ble"
pkg = types.ModuleType("rd200pkg"); pkg.__path__ = [str(pkg_dir)]; sys.modules["rd200pkg"] = pkg
for mod in ("const", "parser"):
    spec = importlib.util.spec_from_file_location("rd200pkg." + mod, pkg_dir / (mod + ".py"))
    m = importlib.util.module_from_spec(spec); sys.modules["rd200pkg." + mod] = m; spec.loader.exec_module(m)
parser = sys.modules["rd200pkg.parser"]
parser.logger = logging.getLogger("x")


class FakeClient:
    def __init__(self, frames):
        self.frames, self.handler = frames, None
    async def start_notify(self, uuid, handler): self.handler = handler
    async def stop_notify(self, uuid): pass
    async def write_gatt_char(self, uuid, data):
        f = self.frames.get(data[0])
        if f is not None: self.handler(None, bytearray(f))


rng = random.Random(200)
cases = []

async def main():
    for i in range(12):
        v2 = i < 9
        if v2:
            f50 = bytes([0x50, 10]) + struct.pack("<5H", *[rng.randrange(0, 2000 if k < 3 else 65536) for k in range(5)])
            f40 = bytearray(rng.randbytes(68)); f40[0] = 0x40
            f40[16:21] = b"RD200"; f40[22:30] = b"V3.0.2  "[:8] if i % 2 else b"V1.2.4.0"
            f51 = bytes([0x51, 14, 0, 0]) + struct.pack("<I", rng.randrange(0, 600000)) + rng.randbytes(8)
            frames = {0x50: f50, 0x40: bytes(f40), 0x51: f51}
            if i == 8:   # short replies are ignored
                frames = {0x50: f50[:11], 0x40: bytes(f40[:60]), 0x51: f51[:15]}
            d = parser.RD200BluetoothDeviceData(logging.getLogger("x"), is_metric=True)
            dev = parser.RD200Device()
            c = FakeClient(frames)
            dev = await d._get_radon(c, dev); dev = await d._get_radon_peak(c, dev); dev = await d._get_radon_uptime(c, dev)
            cases.append({"version": 2, "unit": "Bq", "replies": {k: v.hex() for k, v in
                          {"50": frames[0x50], "40": frames[0x40], "51": frames[0x51]}.items()},
                          "sensors": dev.sensors, "sw": dev.sw_version, "hw": dev.hw_version})
        else:
            f50 = bytes([0x50, 12]) + struct.pack("<3f", *[rng.uniform(0, 20) for _ in range(3)]) + rng.randbytes(6)
            f51 = bytes([0x51, 14, 0, 0]) + struct.pack("<I", rng.randrange(0, 600000)) + rng.randbytes(4) + struct.pack("<f", rng.uniform(0, 40))
            frames = {0x50: f50, 0x51: f51}
            d = parser.RD200BluetoothDeviceData(logging.getLogger("x"), is_metric=False)
            dev = parser.RD200Device()
            c = FakeClient(frames)
            dev = await d._get_radon_oldVersion(c, dev); dev = await d._get_radon_peak_uptime_oldVersion(c, dev)
            cases.append({"version": 1, "unit": "pCi", "replies": {"50": f50.hex(), "51": f51.hex()},
                          "sensors": dev.sensors})

asyncio.run(main())
json.dump(cases, open(sys.argv[2], "w"), indent=1)
print("wrote", len(cases), "cases")

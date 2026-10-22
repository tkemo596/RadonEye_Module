package com.arringtonautomation.ignition.radoneye.rd200;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline checks: the decoder against jdeath/rd200v2's own parser (fixtures/parser_reference.json,
 * from make_reference_vectors.py), and the helper-process handling against fake_helper.py.
 *
 * <pre>
 * java -cp out com.arringtonautomation.ignition.radoneye.rd200.RadonEyeSelfCheck tools
 * </pre>
 */
public final class RadonEyeSelfCheck {

  private static int passed;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    Path tools = Path.of(args.length > 0 ? args[0] : "tools");
    decoder(tools.resolve("fixtures/parser_reference.json"));
    realDevice(tools.resolve("fixtures/real_fr_r20.json"));
    helper(tools.resolve("fake_helper.py"));
    install();

    System.out.println();
    System.out.println(passed + " checks passed, " + failures.size() + " failed");
    failures.forEach(f -> System.out.println("  FAIL " + f));
    System.exit(failures.isEmpty() ? 0 : 1);
  }

  private static void decoder(Path fixture) throws Exception {
    @SuppressWarnings("unchecked")
    List<Object> cases = (List<Object>) Json.parse(Files.readString(fixture));
    int n = 0;
    for (Object o : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Object> c = (Map<String, Object>) o;
      n++;
      int version = Json.integer(c, "version", 0);
      Rd200Reading r = Rd200Reading.fromHelper(Map.of("version", (long) version, "replies", Json.obj(c, "replies")));
      Map<String, Object> s = Json.obj(c, "sensors");
      boolean pci = "pCi".equals(Json.str(c, "unit"));
      String label = "case " + n + " (V" + version + ")";
      check(label + ": radon now", near(Rd200Reading.inUnits(r.radonBq, pci), s.get("radon")),
          r.radonBq + " vs " + s.get("radon"));
      check(label + ": 1-day", near(Rd200Reading.inUnits(r.day1Bq, pci), s.get("radon_1day_level")));
      check(label + ": 1-month", near(Rd200Reading.inUnits(r.month1Bq, pci), s.get("radon_1month_level")));
      check(label + ": peak", near(Rd200Reading.inUnits(r.peakBq, pci), s.get("radon_peak")),
          r.peakBq + " vs " + s.get("radon_peak"));
      Object up = s.get("radon_uptime");
      check(label + ": uptime", up == null ? r.uptimeMinutes == null
          : r.uptimeMinutes != null && r.uptimeMinutes * 60 == ((Number) up).longValue());
      if (version == 2) {
        check(label + ": pulse counts", same(r.pulsesNow, s.get("radon_C_now")) && same(r.pulsesLast, s.get("radon_C_last")));
        String sw = Json.str(c, "sw");
        if (sw != null && !sw.isEmpty()) {
          check(label + ": firmware/hardware text", sw.trim().equals(r.firmware) && Json.str(c, "hw").equals(r.hardware),
              r.firmware + "/" + r.hardware);
        }
      }
    }
    Rd200Reading only40 = new Rd200Reading();
    only40.version = 2;
    byte[] f = new byte[68];
    f[0] = 0x40;
    f[33] = 100;
    f[35] = (byte) 200;
    f[37] = 44;
    f[38] = 1;
    only40.apply("40", f);
    check("V2: 0x40 frame supplies now/day/month when 0x50 is missing (ESPHome offsets)",
        only40.radonBq == 100.0 && only40.day1Bq == 200.0 && only40.month1Bq == 300.0);
    check("units: 148 Bq/m3 = 4.0 pCi/L (EPA action level)", Rd200Reading.inUnits(148.0, true) == 4.0);
    Rd200Reading u = new Rd200Reading();
    u.uptimeMinutes = 1440L * 3 + 245;
    check("uptime text", "3d 04:05".equals(u.uptimeText()));
    check("version from name when the helper does not say", Rd200Reading.fromHelper(Map.of("name", "FR:R20:SN1234")).version == 1
        && Rd200Reading.fromHelper(Map.of("name", "FR:RU12345")).version == 2);
  }

  /** The first reading from a real detector (RD200 version 1, FR:R20), 21 Oct 2026. */
  private static void realDevice(Path fixture) throws Exception {
    Rd200Reading r = Rd200Reading.fromHelper(Json.parseObject(Files.readString(fixture)));
    check("real FR:R20: version 1, name trimmed", r.version == 1 && "FR:R20:SN0000".equals(r.name), r.name);
    check("real FR:R20: now 1.02 pCi/L", Rd200Reading.inUnits(r.radonBq, true) == 1.02, String.valueOf(Rd200Reading.inUnits(r.radonBq, true)));
    check("real FR:R20: 1-day 1.32, 1-month 1.05", Rd200Reading.inUnits(r.day1Bq, true) == 1.32
        && Rd200Reading.inUnits(r.month1Bq, true) == 1.05);
    check("real FR:R20: peak 3.08", Rd200Reading.inUnits(r.peakBq, true) == 3.08);
    check("real FR:R20: uptime 482d 09:09", "482d 09:09".equals(r.uptimeText()), r.uptimeText());
    check("real FR:R20: no pulse counts on version 1", r.pulsesNow == null && r.firmware == null);
  }

  private static void helper(Path fake) throws Exception {
    Rd200Helper ok = fakeHelper(fake, "ok");
    List<Map<String, Object>> found = ok.scan(1);
    check("scan: parsed, strongest first", found.size() == 2 && "C4:7C:8D:00:00:02".equals(Json.str(found.get(0), "address")));
    Rd200Reading r = ok.read("C4:7C:8D:00:00:02", 5);
    check("read: noise before the JSON line ignored, reply decoded",
        r.radonBq == 93.0 && r.day1Bq == 1838.0 && r.month1Bq == 416.0 && r.pulsesNow == 3979
            && r.uptimeMinutes == 12133184L, r.radonBq + " " + r.day1Bq + " " + r.uptimeMinutes);
    check("read: address and name carried", "C4:7C:8D:00:00:02".equals(r.address) && "FR:RU0002".equals(r.name));
    check("helper error message surfaces", message(() -> fakeHelper(fake, "error").read("C4:7C:8D:00:00:09", 5))
        .startsWith("No Bluetooth LE device"));
    check("no radon reply is an error with the helper's reasons",
        message(() -> fakeHelper(fake, "noreply").read("x", 5)).contains("no reply to 50"));
    String g = message(() -> fakeHelper(fake, "garbage").read("x", 5));
    check("non-JSON output reported with exit code and stderr", g.contains("exited 3") && g.contains("AsTask"), g);
    long t0 = System.currentTimeMillis();
    String h = message(() -> fakeHelper(fake, "hang").run(List.of(), 2));
    check("hung helper killed at the timeout", h.contains("did not finish") && System.currentTimeMillis() - t0 < 6000, h);
    check("2 MB of stderr does not block the process", fakeHelper(fake, "noisy").scan(1).isEmpty());
    check("missing executable reported", message(() -> new Rd200Helper(List.of("/no/such/powershell.exe")).scan(1))
        .startsWith("Cannot start"));
  }

  private static void install() throws Exception {
    Path dir = Files.createTempDirectory("rd200");
    Path p = Rd200Helper.install(dir.resolve("var").resolve("rd200.ps1"));
    String text = Files.readString(p);
    check("install: script extracted from the module", text.contains("FromBluetoothAddressAsync"));
    check("install: CRLF line endings for Windows", text.contains("\r\n") && !text.replace("\r\n", "").contains("\n"));
    long m = Files.getLastModifiedTime(p).toMillis();
    Thread.sleep(20);
    Rd200Helper.install(p);
    check("install: unchanged file left alone", Files.getLastModifiedTime(p).toMillis() == m);
    Files.writeString(p, "tampered");
    Rd200Helper.install(p);
    check("install: changed file replaced", Files.readString(p).contains("FromBluetoothAddressAsync"));
  }

  private static Rd200Helper fakeHelper(Path fake, String mode) {
    return new Rd200Helper(List.of("python3", "-I", fake.toString(), mode));
  }

  private interface Thrower {
    void run() throws Exception;
  }

  private static String message(Thrower t) {
    try {
      t.run();
      return "(no exception)";
    } catch (Exception e) {
      return String.valueOf(e.getMessage());
    }
  }

  private static boolean near(Double mine, Object theirs) {
    if (mine == null || theirs == null) {
      return mine == null && theirs == null;
    }
    return Math.abs(mine - ((Number) theirs).doubleValue()) <= 0.011;
  }

  private static boolean same(Integer mine, Object theirs) {
    if (mine == null || theirs == null) {
      return mine == null && theirs == null;
    }
    return mine == ((Number) theirs).intValue();
  }

  private static void check(String name, boolean ok) {
    check(name, ok, null);
  }

  private static void check(String name, boolean ok, String detail) {
    if (ok) {
      passed++;
    } else {
      failures.add(name + (detail == null ? "" : " -> " + detail));
    }
  }
}

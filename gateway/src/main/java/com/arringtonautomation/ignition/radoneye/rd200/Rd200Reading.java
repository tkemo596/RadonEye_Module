package com.arringtonautomation.ignition.radoneye.rd200;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Decodes the RD200 replies that rd200.ps1 returns as hex. A port of the parser in
 * github.com/jdeath/rd200v2 (rd200_ble/parser.py), cross-checked against ESPHome's radon_eye_rd200.
 *
 * <p>Version 2 (serials FR:RU, FR:RE, FR:GI, FR:H*, FR:I*, FR:J*, FR:K* ...) reports radon in
 * Bq/m3 as 16-bit integers. Version 1 (FR:R2) reports pCi/L as 32-bit floats. All little-endian.
 *
 * <pre>
 * V2 0x50 (12 bytes): [2] now  [4] 1-day  [6] 1-month  [8] pulses now  [10] pulses last 10 min  (u16)
 * V2 0x40 (68 bytes): [16..20] hardware  [22..29] firmware  [51] peak (u16)
 * V2 0x51 (16 bytes): [4] uptime minutes (u32)
 * V1 0x50 (>=14 b):   [2] now  [6] 1-day  [10] 1-month  (float pCi/L)
 * V1 0x51 (>=16 b):   [4] uptime minutes (u32)  [12] peak (float pCi/L)
 * </pre>
 */
public final class Rd200Reading {

  /** 1 pCi/L = 37 Bq/m3, exactly. */
  public static final double BQ_PER_PCI = 37.0;

  public int version;
  public String name;
  public String address;
  /** Radon values in Bq/m3; null when the device did not report them. */
  public Double radonBq;
  public Double day1Bq;
  public Double month1Bq;
  public Double peakBq;
  public Integer pulsesNow;
  public Integer pulsesLast;
  public Long uptimeMinutes;
  public String firmware;
  public String hardware;

  /** Decodes a read-mode helper result: {version, name, address, replies:{"50":"hex",...}}. */
  public static Rd200Reading fromHelper(Map<String, Object> result) {
    Rd200Reading r = new Rd200Reading();
    String n = Json.str(result, "name");
    r.name = n == null ? null : n.trim(); // the RD200 pads its name with spaces
    r.address = Json.str(result, "address");
    int v = Json.integer(result, "version", 0);
    if (v == 0) {
      v = r.name != null && r.name.startsWith("FR:R2") ? 1 : 2;
    }
    r.version = v;
    Map<String, Object> replies = Json.obj(result, "replies");
    if (replies != null) {
      for (Map.Entry<String, Object> e : replies.entrySet()) {
        if (e.getValue() instanceof String hex && !hex.isEmpty()) {
          r.apply(e.getKey().toLowerCase(java.util.Locale.ROOT), HexFormat.of().parseHex(hex));
        }
      }
    }
    return r;
  }

  /** Applies one reply. Replies of an unexpected length are ignored, as in parser.py. */
  public void apply(String command, byte[] d) {
    ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    if (version == 1) {
      switch (command) {
        case "50" -> {
          if (d.length >= 14) {
            radonBq = round2(b.getFloat(2) * BQ_PER_PCI);
            day1Bq = round2(b.getFloat(6) * BQ_PER_PCI);
            month1Bq = round2(b.getFloat(10) * BQ_PER_PCI);
          }
        }
        case "51" -> {
          if (d.length >= 16) {
            uptimeMinutes = b.getInt(4) & 0xFFFFFFFFL;
            peakBq = round2(b.getFloat(12) * BQ_PER_PCI);
          }
        }
        default -> { }
      }
      return;
    }
    switch (command) {
      case "50" -> {
        if (d.length == 12) {
          radonBq = (double) u16(b, 2);
          day1Bq = (double) u16(b, 4);
          month1Bq = (double) u16(b, 6);
          pulsesNow = u16(b, 8);
          pulsesLast = u16(b, 10);
        }
      }
      case "40" -> {
        if (d.length == 68) {
          peakBq = (double) u16(b, 51);
          firmware = ascii(d, 22, 30);
          hardware = ascii(d, 16, 21);
          // The same frame also carries now / 1-day / 1-month (ESPHome reads them here); use them
          // only if the 0x50 reply was missing.
          if (radonBq == null) {
            radonBq = (double) u16(b, 33);
            day1Bq = (double) u16(b, 35);
            month1Bq = (double) u16(b, 37);
          }
        }
      }
      case "51" -> {
        if (d.length == 16) {
          uptimeMinutes = b.getInt(4) & 0xFFFFFFFFL;
        }
      }
      default -> { }
    }
  }

  public boolean hasRadon() {
    return radonBq != null;
  }

  /** Bq/m3 to the display unit. */
  public static Double inUnits(Double bq, boolean pci) {
    if (bq == null) {
      return null;
    }
    return pci ? round2(bq / BQ_PER_PCI) : round2(bq);
  }

  /** "3d 04:05", as the Home Assistant integration shows uptime. */
  public String uptimeText() {
    if (uptimeMinutes == null) {
      return null;
    }
    long m = uptimeMinutes;
    return String.format("%dd %02d:%02d", m / 1440, (m % 1440) / 60, m % 60);
  }

  private static int u16(ByteBuffer b, int off) {
    return b.getShort(off) & 0xFFFF;
  }

  private static String ascii(byte[] d, int from, int to) {
    String s = new String(d, from, to - from, StandardCharsets.US_ASCII);
    return s.replaceAll("[^\\x20-\\x7E]", "").trim();
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }
}

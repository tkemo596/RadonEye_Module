package com.arringtonautomation.ignition.radoneye.rd200;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs rd200.ps1 (the Bluetooth transport) as a child process and parses its one line of JSON.
 *
 * <p>Java has no Bluetooth LE API, and the Windows one (WinRT) is easiest to reach from Windows
 * PowerShell 5.1, which every Windows 10/11 machine has. Each reading is one short-lived process:
 * connect, query, disconnect - nothing stays attached to the detector between readings.
 */
public final class Rd200Helper {

  /** Everything before the script's own arguments, e.g. powershell.exe ... -File rd200.ps1. */
  private final List<String> baseCommand;

  public Rd200Helper(List<String> baseCommand) {
    this.baseCommand = List.copyOf(baseCommand);
  }

  /** Windows PowerShell 5.1 running {@code script}. */
  public static Rd200Helper windows(Path script) {
    String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
    String ps = root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    return new Rd200Helper(List.of(ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", script.toString()));
  }

  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
  }

  /**
   * Copies the bundled script to {@code target} if it is missing or different, and returns it. The
   * script lives inside the module jar, so it has to be put on disk for powershell.exe.
   */
  public static Path install(Path target) throws IOException {
    byte[] content;
    try (InputStream in = Rd200Helper.class.getResourceAsStream("/com/arringtonautomation/ignition/radoneye/rd200.ps1")) {
      if (in == null) {
        throw new IOException("rd200.ps1 is missing from the module");
      }
      content = in.readAllBytes();
    }
    // CRLF on disk: powershell.exe does not need it, but people open the file in Notepad.
    String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n");
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
      Files.createDirectories(target.getParent());
      Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
      Files.write(tmp, bytes);
      Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  /** Nearby RadonEye detectors: [{address, name, rssi}], strongest first. */
  public List<Map<String, Object>> scan(int seconds) throws Rd200Exception {
    Map<String, Object> r = run(List.of("-Scan", "-Seconds", Integer.toString(seconds)), seconds + 30);
    List<Map<String, Object>> out = new ArrayList<>();
    List<Object> devices = Json.list(r, "devices");
    if (devices != null) {
      for (Object o : devices) {
        if (o instanceof Map<?, ?> m) {
          @SuppressWarnings("unchecked")
          Map<String, Object> mm = (Map<String, Object>) m;
          out.add(mm);
        }
      }
    }
    out.sort((a, b) -> Integer.compare(Json.integer(b, "rssi", -200), Json.integer(a, "rssi", -200)));
    return out;
  }

  /** One reading. Throws if the helper failed outright or no radon value came back. */
  public Rd200Reading read(String address, int timeoutSeconds) throws Rd200Exception {
    Map<String, Object> r = run(List.of("-Address", address, "-TimeoutSeconds", Integer.toString(timeoutSeconds)),
        timeoutSeconds * 4 + 30);
    Rd200Reading reading = Rd200Reading.fromHelper(r);
    if (!reading.hasRadon()) {
      List<Object> errors = Json.list(r, "errors");
      throw new Rd200Exception("No radon reading from " + address
          + (errors == null || errors.isEmpty() ? "" : ": " + errors));
    }
    return reading;
  }

  Map<String, Object> run(List<String> args, int processTimeoutSeconds) throws Rd200Exception {
    List<String> cmd = new ArrayList<>(baseCommand);
    cmd.addAll(args);
    Process p;
    try {
      p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
    } catch (IOException e) {
      throw new Rd200Exception("Cannot start " + baseCommand.get(0) + ": " + e.getMessage());
    }
    try {
      p.getOutputStream().close();
    } catch (IOException ignored) {
      // the helper reads nothing from stdin
    }
    // Drain both streams on their own threads so a chatty stderr can't block the process.
    Drain out = new Drain(p.getInputStream());
    Drain err = new Drain(p.getErrorStream());
    out.start();
    err.start();
    try {
      if (!p.waitFor(processTimeoutSeconds, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new Rd200Exception("Bluetooth helper did not finish within " + processTimeoutSeconds + " s");
      }
      out.join(2000);
      err.join(2000);
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new Rd200Exception("Interrupted");
    }

    String stdout = out.text().trim();
    String json = null;
    for (String line : stdout.split("\\R")) {
      if (line.trim().startsWith("{")) {
        json = line.trim();
      }
    }
    if (json == null) {
      String e = err.text().trim();
      throw new Rd200Exception("Bluetooth helper exited " + p.exitValue() + " without a result"
          + (e.isEmpty() ? "" : ": " + shorten(e)) + (stdout.isEmpty() ? "" : " / " + shorten(stdout)));
    }
    Map<String, Object> r;
    try {
      r = Json.parseObject(json);
    } catch (IllegalArgumentException e) {
      throw new Rd200Exception("Bluetooth helper printed invalid JSON: " + shorten(json));
    }
    String error = Json.str(r, "error");
    if (error != null) {
      throw new Rd200Exception(error);
    }
    return r;
  }

  private static String shorten(String s) {
    String one = s.replaceAll("\\s+", " ");
    return one.length() > 300 ? one.substring(0, 300) + "..." : one;
  }

  private static final class Drain extends Thread {
    private final InputStream in;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    Drain(InputStream in) {
      this.in = in;
      setDaemon(true);
    }

    @Override
    public void run() {
      byte[] b = new byte[4096];
      try {
        int n;
        while ((n = in.read(b)) >= 0) {
          synchronized (buf) {
            buf.write(b, 0, n);
          }
        }
      } catch (IOException ignored) {
        // process gone
      }
    }

    String text() {
      synchronized (buf) {
        // Windows PowerShell writes the console code page; the JSON itself is ASCII.
        return buf.toString(StandardCharsets.UTF_8);
      }
    }
  }
}

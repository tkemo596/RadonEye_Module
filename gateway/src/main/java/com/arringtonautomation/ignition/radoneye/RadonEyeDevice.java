package com.arringtonautomation.ignition.radoneye;

import com.arringtonautomation.ignition.radoneye.rd200.Json;
import com.arringtonautomation.ignition.radoneye.rd200.Rd200Exception;
import com.arringtonautomation.ignition.radoneye.rd200.Rd200Helper;
import com.arringtonautomation.ignition.radoneye.rd200.Rd200Reading;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One RadonEye RD200, read over Bluetooth LE through rd200.ps1 every few minutes.
 *
 * <p>Each reading is a short connection (connect, three queries, disconnect, a few seconds), so
 * the Ecosense phone app can still connect in between. The tag tree is fixed; values come from
 * the last successful reading.
 */
public class RadonEyeDevice extends ManagedAddressSpaceWithLifecycle implements Device {

  private static final Logger logger = LoggerFactory.getLogger(RadonEyeDevice.class);

  private static final long RETRY_AFTER_FAILURE_MS = 60_000;
  private static final int SCAN_SECONDS = 10;
  private static final DateTimeFormatter LOCAL_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private enum Kind { NUMBER, BOOLEAN, STRING }

  private record Tag(String name, Kind kind, boolean writable, String description) {}

  private static final List<Tag> TAGS = List.of(
      new Tag("Radon", Kind.NUMBER, false, "Radon now (the RD200's 10-minute value)"),
      new Tag("Radon1Day", Kind.NUMBER, false, "1-day average"),
      new Tag("Radon1Month", Kind.NUMBER, false, "1-month average (EPA guidance is based on long-term averages)"),
      new Tag("RadonPeak", Kind.NUMBER, false, "Peak since the detector was last reset"),
      new Tag("Units", Kind.STRING, false, "pCi/L or Bq/m3"),
      new Tag("PulsesNow", Kind.NUMBER, false, "Ion-chamber pulses in the current 10-minute window (version 2 only)"),
      new Tag("PulsesLast10Min", Kind.NUMBER, false, "Pulses in the last complete 10-minute window (version 2 only)"),
      new Tag("UptimeMinutes", Kind.NUMBER, false, "Minutes since the detector was powered on or reset"),
      new Tag("Uptime", Kind.STRING, false, "Uptime as days hh:mm"),
      new Tag("Firmware", Kind.STRING, false, "Firmware version (version 2 only)"),
      new Tag("Hardware", Kind.STRING, false, "Hardware model (version 2 only)"),
      new Tag("ProtocolVersion", Kind.NUMBER, false, "1 = original RD200 (FR:R2...), 2 = current RD200"),
      new Tag("Name", Kind.STRING, false, "Bluetooth name (FR:... serial)"),
      new Tag("Address", Kind.STRING, false, "Bluetooth address in use"),
      new Tag("LastReading", Kind.STRING, false, "Gateway time of the last successful reading"),
      new Tag("LastReadOk", Kind.BOOLEAN, false, "The most recent attempt succeeded"),
      new Tag("Status", Kind.STRING, false, "Status or the last error"),
      new Tag("ReadNow", Kind.BOOLEAN, true, "Write true to read the detector now"));

  private final DeviceContext context;
  private final RadonEyeDeviceConfig config;
  private final SubscriptionModel subscriptionModel;
  private final List<NodeId> managedNodes = new ArrayList<>();

  /** Tags only the current RD200 (version 2) reports; left out for an original (version 1) one. */
  private static final Set<String> V2_ONLY = Set.of("PulsesNow", "PulsesLast10Min", "Firmware", "Hardware");

  /** Detector version the tag tree was built for; 0 = not known yet (all tags). */
  private volatile int treeVersion;

  private ScheduledExecutorService executor;
  private Rd200Helper helper;

  private volatile Rd200Reading last;
  private volatile Instant lastTime;
  private volatile boolean lastOk;
  private volatile String status = "Starting";
  private volatile String address;
  private volatile boolean readRequested;
  private long nextReadMs;

  public RadonEyeDevice(DeviceContext context, RadonEyeDeviceConfig config) {
    super(context.getServer());
    this.context = context;
    this.config = config;
    String a = config.detector().address();
    this.address = a == null || a.isBlank() ? null : normalise(a);

    this.subscriptionModel = new SubscriptionModel(context.getServer(), this);
    getLifecycleManager().addLifecycle(subscriptionModel);
    getLifecycleManager().addStartupTask(this::onStartup);
    getLifecycleManager().addShutdownTask(this::onShutdown);
  }

  static String normalise(String a) {
    String hex = a.trim().replaceAll("[^0-9A-Fa-f]", "").toUpperCase(java.util.Locale.ROOT);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i + 1 < hex.length(); i += 2) {
      if (sb.length() > 0) {
        sb.append(':');
      }
      sb.append(hex, i, i + 2);
    }
    return sb.toString();
  }

  @Override
  public String getStatus() {
    return status;
  }

  // ---------------------------------------------------------------- lifecycle

  private void onStartup() {
    buildNodes(0);

    if (!Rd200Helper.isWindows()) {
      status = "Requires a Windows gateway (Bluetooth goes through Windows' own Bluetooth APIs)";
    } else {
      try {
        Path script = Paths.get(System.getProperty("data.dir", "data"), "var", "radoneye", "rd200.ps1")
            .toAbsolutePath();
        helper = Rd200Helper.windows(Rd200Helper.install(script));
        logger.info("Device {}: Bluetooth helper at {}", context.getName(), script);
      } catch (IOException e) {
        status = "Cannot install the Bluetooth helper: " + e.getMessage();
        logger.error("Device {}: {}", context.getName(), status, e);
      }
    }

    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "radoneye-" + context.getName());
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);

    onDataItemsCreated(context.getSubscriptionModel().getDataItems(context.getName()));
  }

  private void onShutdown() {
    context.getSubscriptionModel().getDataItems(context.getName())
        .forEach(item -> item.setQuality(new StatusCode(StatusCodes.Uncertain_LastUsableValue)));
    ScheduledExecutorService e = executor;
    executor = null;
    if (e != null) {
      e.shutdownNow();
    }
  }

  private void tick() {
    if (helper == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (!readRequested && now < nextReadMs) {
      return;
    }
    readRequested = false;
    try {
      readOnce();
      nextReadMs = System.currentTimeMillis() + Math.max(config.readings().intervalMinutes(), 1) * 60_000L;
    } catch (Rd200Exception e) {
      lastOk = false;
      status = "Read failed: " + e.getMessage();
      logger.warn("Device {}: {}", context.getName(), status);
      long interval = Math.max(config.readings().intervalMinutes(), 1) * 60_000L;
      nextReadMs = System.currentTimeMillis() + Math.min(RETRY_AFTER_FAILURE_MS, interval);
    } catch (RuntimeException e) {
      lastOk = false;
      status = "Error: " + e;
      logger.error("Device {}: unexpected error", context.getName(), e);
      nextReadMs = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
    }
  }

  private void readOnce() throws Rd200Exception {
    int timeout = Math.max(config.detector().timeoutSeconds(), 5);
    boolean configured = config.detector().address() != null && !config.detector().address().isBlank();
    if (address == null) {
      status = "Searching for a RadonEye...";
      List<Map<String, Object>> found = helper.scan(SCAN_SECONDS);
      if (found.isEmpty()) {
        throw new Rd200Exception("No RadonEye (FR:...) found within " + SCAN_SECONDS
            + " s. Is Bluetooth on, the detector in range, and the Ecosense app closed?");
      }
      address = Json.str(found.get(0), "address");
      logger.info("Device {}: using {} at {} (RSSI {}){}", context.getName(), Json.str(found.get(0), "name"),
          address, found.get(0).get("rssi"),
          found.size() > 1 ? "; " + found.size() + " detectors in range - set the address to pick one" : "");
    }
    status = "Reading " + address + "...";
    try {
      Rd200Reading r = helper.read(address, timeout);
      last = r;
      if (r.version != treeVersion) {
        buildNodes(r.version);
      }
      lastTime = Instant.now();
      lastOk = true;
      status = "OK - " + fmt(Rd200Reading.inUnits(r.radonBq, config.readings().picoCuries())) + " " + units()
          + " at " + LOCAL_TIME.format(lastTime);
    } catch (Rd200Exception e) {
      if (!configured) {
        address = null; // search again next time; the detector may have changed address
      }
      throw e;
    }
  }

  private String units() {
    return config.readings().picoCuries() ? "pCi/L" : "Bq/m3";
  }

  private static String fmt(Double d) {
    return d == null ? "-" : String.format("%.2f", d);
  }

  // --------------------------------------------------------------- node tree

  private synchronized void buildNodes(int version) {
    for (int i = managedNodes.size() - 1; i >= 0; i--) {
      try {
        getNodeManager().removeNode(managedNodes.get(i));
      } catch (RuntimeException e) {
        logger.debug("Ignoring an error removing node: {}", e.toString());
      }
    }
    managedNodes.clear();
    treeVersion = version;

    UaFolderNode root = new UaFolderNode(getNodeContext(), context.nodeId(context.getName()),
        context.qualifiedName(String.format("[%s]", context.getName())),
        new LocalizedText(String.format("[%s]", context.getName())));
    getNodeManager().addNode(root);
    managedNodes.add(root.getNodeId());
    root.addReference(new Reference(root.getNodeId(), NodeIds.Organizes,
        context.getRootNodeId().expanded(), Reference.Direction.INVERSE));

    for (Tag t : TAGS) {
      if (version == 1 && V2_ONLY.contains(t.name())) {
        continue;
      }
      Set<AccessLevel> access = t.writable() ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY;
      OpcUaDataType type = switch (t.kind()) {
        case NUMBER -> OpcUaDataType.Double;
        case BOOLEAN -> OpcUaDataType.Boolean;
        case STRING -> OpcUaDataType.String;
      };
      UaVariableNode node = UaVariableNode.build(getNodeContext(), b ->
          b.setNodeId(context.nodeId(t.name()))
              .setBrowseName(context.qualifiedName(t.name()))
              .setDisplayName(new LocalizedText(t.name()))
              .setDescription(new LocalizedText(t.description()))
              .setDataType(type.getNodeId())
              .setTypeDefinition(NodeIds.BaseDataVariableType)
              .setAccessLevel(access)
              .setUserAccessLevel(access)
              .build());
      node.getFilterChain().addLast(AttributeFilters.getValue(ctx -> readValue(t)));
      if (t.writable()) {
        node.getFilterChain().addLast(AttributeFilters.setValue((ctx, value) -> {
          Object raw = value.getValue() == null ? null : value.getValue().getValue();
          if (Boolean.TRUE.equals(raw) || (raw instanceof Number n && n.doubleValue() != 0)) {
            readRequested = true;
          }
          ctx.setAttribute(AttributeId.Value, value);
        }));
      }
      getNodeManager().addNode(node);
      managedNodes.add(node.getNodeId());
      root.addOrganizes(node);
    }
  }

  // ------------------------------------------------------------------- reads

  private DataValue readValue(Tag t) {
    switch (t.name()) {
      case "Status": return good(status, DateTime.now());
      case "LastReadOk": return good(lastOk, DateTime.now());
      case "ReadNow": return good(Boolean.FALSE, DateTime.now());
      case "Units": return good(units(), DateTime.now());
      case "Address": return good(address == null ? "" : address, DateTime.now());
      default: break;
    }
    Rd200Reading r = last;
    if (r == null) {
      return new DataValue(Variant.NULL_VALUE, new StatusCode(StatusCodes.Bad_WaitingForInitialData), DateTime.now());
    }
    boolean pci = config.readings().picoCuries();
    Object v = switch (t.name()) {
      case "Radon" -> Rd200Reading.inUnits(r.radonBq, pci);
      case "Radon1Day" -> Rd200Reading.inUnits(r.day1Bq, pci);
      case "Radon1Month" -> Rd200Reading.inUnits(r.month1Bq, pci);
      case "RadonPeak" -> Rd200Reading.inUnits(r.peakBq, pci);
      case "PulsesNow" -> r.pulsesNow == null ? null : (double) r.pulsesNow;
      case "PulsesLast10Min" -> r.pulsesLast == null ? null : (double) r.pulsesLast;
      case "UptimeMinutes" -> r.uptimeMinutes == null ? null : (double) r.uptimeMinutes;
      case "Uptime" -> r.uptimeText();
      case "Firmware" -> r.firmware;
      case "Hardware" -> r.hardware;
      case "ProtocolVersion" -> (double) r.version;
      case "Name" -> r.name;
      case "LastReading" -> lastTime == null ? "" : LOCAL_TIME.format(lastTime);
      default -> null;
    };
    DateTime when = lastTime == null ? DateTime.now() : new DateTime(Date.from(lastTime));
    if (v == null) {
      // Not reported by this model/firmware (e.g. pulses on version 1).
      return new DataValue(Variant.NULL_VALUE, new StatusCode(StatusCodes.Bad_WaitingForInitialData), when);
    }
    StatusCode q = lastOk ? StatusCode.GOOD : new StatusCode(StatusCodes.Uncertain_LastUsableValue);
    return new DataValue(new Variant(v), q, when);
  }

  private static DataValue good(Object v, DateTime t) {
    return new DataValue(v == null ? Variant.NULL_VALUE : new Variant(v), StatusCode.GOOD, t);
  }

  // ----------------------------------------------------- subscription plumbing

  @Override
  public void onDataItemsCreated(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsCreated(dataItems);
  }

  @Override
  public void onDataItemsModified(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsModified(dataItems);
  }

  @Override
  public void onDataItemsDeleted(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsDeleted(dataItems);
  }

  @Override
  public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
    subscriptionModel.onMonitoringModeChanged(monitoredItems);
  }
}

package com.arringtonautomation.ignition.radoneye;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import java.util.Optional;
import java.util.Set;

/** Registers "RadonEye RD200" as a device type in Ignition's OPC UA server. */
public class RadonEyeDeviceExtensionPoint extends DeviceExtensionPoint<RadonEyeDeviceConfig> {

  public static final String TYPE_ID = "com.arringtonautomation.radoneye.RadonEyeDevice";

  public RadonEyeDeviceExtensionPoint() {
    super(
        TYPE_ID,
        "RadonEyeDevice.Meta.DisplayName",
        "RadonEyeDevice.Meta.Description",
        RadonEyeDeviceConfig.class);
  }

  @Override
  protected Device createDevice(
      DeviceContext context, DeviceProfileConfig profileConfig, RadonEyeDeviceConfig deviceConfig) {

    return new RadonEyeDevice(context, deviceConfig);
  }

  @Override
  public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
    return Optional.of(
        new ExtensionPointResourceForm(
            DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
            "Device Connection",
            TYPE_ID,
            SchemaUtil.fromType(DeviceProfileConfig.class),
            SchemaUtil.fromType(RadonEyeDeviceConfig.class),
            Set.of()));
  }

  @Override
  protected void validate(RadonEyeDeviceConfig config, Builder errors) {
    String a = config.detector().address();
    errors.check(a == null || a.isBlank() || isMac(a),
        "Bluetooth Address must look like C4:7C:8D:12:34:56, or be blank to search");
    errors.check(config.detector().timeoutSeconds() >= 5 && config.detector().timeoutSeconds() <= 120,
        "Bluetooth Timeout must be 5-120 seconds");
    errors.check(config.readings().intervalMinutes() >= 1 && config.readings().intervalMinutes() <= 1440,
        "Read Interval must be 1-1440 minutes");
  }

  static boolean isMac(String s) {
    return s.trim().replaceAll("[^0-9A-Fa-f]", "").length() == 12
        && s.trim().matches("[0-9A-Fa-f:\\-]+");
  }
}

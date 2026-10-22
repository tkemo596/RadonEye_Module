package com.arringtonautomation.ignition.radoneye;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import java.util.List;

/** Gateway entry point. Registers the RadonEye RD200 device type with the OPC UA server. */
public class RadonEyeModuleHook extends AbstractDeviceModuleHook {

  @Override
  public void setup(GatewayContext context) {
    super.setup(context);

    BundleUtil.get().addBundle(RadonEyeDevice.class);
  }

  @Override
  public void startup(LicenseState activationState) {
    super.startup(activationState);
  }

  @Override
  public void shutdown() {
    super.shutdown();

    BundleUtil.get().removeBundle(RadonEyeDevice.class);
  }

  @Override
  protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
    return List.of(new RadonEyeDeviceExtensionPoint());
  }

  @Override
  public boolean isMakerEditionCompatible() {
    return true;
  }
}

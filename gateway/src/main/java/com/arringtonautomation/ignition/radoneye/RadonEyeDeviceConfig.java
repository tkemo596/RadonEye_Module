package com.arringtonautomation.ignition.radoneye;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * The device settings form. Every scalar component carries a {@code @FormField} (without one the
 * device page fails with "Application Error"); nested record components must not.
 */
public record RadonEyeDeviceConfig(Detector detector, Readings readings) {

  /** Which RD200, and how to talk to it. */
  public record Detector(
      @FormCategory("DETECTOR")
          @Label("Bluetooth Address")
          @FormField(FormFieldType.TEXT)
          @DefaultValue("")
          @Description(
              "The RD200's Bluetooth address, e.g. C4:7C:8D:12:34:56. Leave blank to use the"
                  + " strongest RadonEye in range (its name starts with FR:); the address found is"
                  + " shown in the Address tag - copy it here if you have more than one.")
          String address,
      @FormCategory("DETECTOR")
          @Label("Bluetooth Timeout (s)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("15")
          @Description("How long to wait for each Bluetooth step (connect, reply).")
          int timeoutSeconds) {}

  /** How often, and in what units. */
  public record Readings(
      @FormCategory("READINGS")
          @Label("Read Interval (min)")
          @FormField(FormFieldType.NUMBER)
          @DefaultValue("10")
          @Description(
              "The RD200 updates its radon value every 10 minutes, so reading more often only adds"
                  + " Bluetooth traffic. 1-1440.")
          int intervalMinutes,
      @FormCategory("READINGS")
          @Label("Show pCi/L")
          @FormField(FormFieldType.CHECKBOX)
          @DefaultValue("true")
          @Description("On: radon tags in pCi/L (US; EPA action level 4.0). Off: Bq/m³ (action level 148).")
          boolean picoCuries) {}
}

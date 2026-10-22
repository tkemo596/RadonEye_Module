package com.arringtonautomation.ignition.radoneye.rd200;

/** A failed scan or reading. The message is meant for the device status line. */
public class Rd200Exception extends Exception {

  private static final long serialVersionUID = 1L;

  public Rd200Exception(String message) {
    super(message);
  }
}

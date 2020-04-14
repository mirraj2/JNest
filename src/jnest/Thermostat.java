package jnest;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import ox.Json;

public class Thermostat extends Device {

  public ThermostatMode mode;

  public Double currentTemperature, targetTemperatureLow, targetTemperature, targetTemperatureHigh;

  public String locationName;

  public Thermostat(String serialNumber) {
    super(serialNumber);
  }

  public Map<String, Object> getData() {
    return ImmutableMap.of("mode", mode,
        "currentTemperature", currentTemperature,
        "targetTemperatureLow", targetTemperatureLow,
        "targetTemperature", targetTemperature,
        "targetTemperatureHigh", targetTemperatureHigh);
  }

  public void update(Json data, Map<String, String> roomNames) {
    Json json = data.getJson("device");
    locationName = roomNames.get(json.get("where_id"));

    json = data.getJson("shared");
    mode = parseMode(json.get("target_temperature_type"));
    currentTemperature = cToF(json.getDouble("current_temperature"));
    targetTemperatureLow = cToF(json.getDouble("target_temperature_low"));
    targetTemperature = cToF(json.getDouble("target_temperature"));
    targetTemperatureHigh = cToF(json.getDouble("target_temperature_high"));
  }

  @Override
  public String toString() {
    return "Thermostat [serialNumber=" + serialNumber + ", mode=" + mode + ", currentTemperature=" + currentTemperature
        + ", targetTemperatureLow=" + targetTemperatureLow + ", targetTemperature=" + targetTemperature
        + ", targetTemperatureHigh=" + targetTemperatureHigh + ", locationName=" + locationName + "]";
  }

  public static double cToF(double c) {
    return c * 1.8 + 32;
  }

  public static double fToC(double f) {
    return (f - 32) / 1.8;
  }

  private static ThermostatMode parseMode(String s) {
    if (s.equals("cool")) {
      return ThermostatMode.COOLING;
    } else if (s.equals("heat")) {
      return ThermostatMode.HEATING;
    } else if (s.equals("range")) {
      return ThermostatMode.RANGE;
    } else if (s.equals("off")) {
      return ThermostatMode.OFF;
    } else {
      throw new RuntimeException("Unhandled mode: " + s);
    }
  }

  public static enum ThermostatMode {
    OFF, COOLING, HEATING, RANGE, FAN;

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

}

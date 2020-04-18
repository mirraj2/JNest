package jnest;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;

import ox.Json;

public class Thermostat extends Device {

  public ThermostatMode mode;

  public Double currentTemperature, targetTemperatureLow, targetTemperature, targetTemperatureHigh;

  public String locationName;

  public boolean isHeatOn, isCoolOn, isFanOn;

  public Thermostat(String serialNumber) {
    super(serialNumber);
  }

  public Map<String, Object> getData() {
    return ImmutableMap.<String, Object>builder()
        .put("mode", mode)
        .put("currentTemperature", currentTemperature)
        .put("targetTemperatureLow", targetTemperatureLow)
        .put("targetTemperature", targetTemperature)
        .put("targetTemperatureHigh", targetTemperatureHigh)
        .put("isHeatOn", isHeatOn)
        .put("isCoolOn", isCoolOn)
        .put("isFanOn", isFanOn)
        .build();
  }

  @Override
  public void update(Json data) {
    super.update(data);

    if (data.hasKey("device")) {
      Json json = data.getJson("device");
      if (json.hasKey("where_name")) {
        locationName = json.get("where_name");
      }
    }

    if (data.hasKey("shared")) {
      Json json = data.getJson("shared");
      mode = parseMode(json.get("target_temperature_type"));
      currentTemperature = cToF(json.getDouble("current_temperature"));
      targetTemperatureLow = cToF(json.getDouble("target_temperature_low"));
      targetTemperature = cToF(json.getDouble("target_temperature"));
      targetTemperatureHigh = cToF(json.getDouble("target_temperature_high"));
      isHeatOn = json.getBoolean("hvac_heater_state");
      isCoolOn = json.getBoolean("hvac_ac_state");
      isFanOn = json.getBoolean("hvac_fan_state");
    }
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
    ThermostatMode ret = modeStrings.get(s);
    checkNotNull(ret, "Unhandled mode: " + s);
    return ret;
  }

  private static final BiMap<String, ThermostatMode> modeStrings = HashBiMap.create();
  static {
    for (ThermostatMode mode : ThermostatMode.values()) {
      modeStrings.put(mode.getNestString(), mode);
    }
  }

  public static enum ThermostatMode {
    OFF, COOLING, HEATING, RANGE, FAN;

    @Override
    public String toString() {
      return name().toLowerCase();
    }

    public String getNestString() {
      if (this == COOLING) {
        return "cool";
      } else if (this == HEATING) {
        return "heat";
      } else {
        return name().toLowerCase();
      }
    }
  }

}

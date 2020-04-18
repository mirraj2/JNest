package jnest;

import java.time.Instant;
import java.util.Map;

import ox.Json;

public abstract class Device {

  public final String serialNumber;

  public int deviceRevisionNumber, sharedRevisionNumber;
  public Instant deviceTimestamp, sharedTimestamp;

  public Device(String serialNumber) {
    this.serialNumber = serialNumber;
  }

  public abstract Map<String, Object> getData();

  public void update(Json data) {
    if (data.hasKey("device")) {
      Json json = data.getJson("device");
      deviceRevisionNumber = json.getInt("object_revision");
      deviceTimestamp = Instant.ofEpochMilli(json.getLong("object_timestamp"));
    }

    if (data.hasKey("shared")) {
      Json json = data.getJson("shared");
      sharedRevisionNumber = json.getInt("object_revision");
      sharedTimestamp = Instant.ofEpochMilli(json.getLong("object_timestamp"));
    }
  }

  @Override
  public String toString() {
    return serialNumber;
  }

}

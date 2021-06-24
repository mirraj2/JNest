package jnest;

import static com.google.common.base.Preconditions.checkState;
import static ox.util.Functions.filter;
import static ox.util.Functions.index;
import static ox.util.Utils.checkNotEmpty;
import static ox.util.Utils.first;
import static ox.util.Utils.normalize;
import static ox.util.Utils.only;
import static ox.util.Utils.second;
import static ox.util.Utils.sleep;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import jnest.NestThermostat.ThermostatMode;
import ox.HttpRequest;
import ox.Json;
import ox.Log;
import ox.Threads;

public class NestClient {

  private String userId, accessToken;
  private Instant lastAccessTokenRefresh = null;
  private String transportUrl;
  private String issueTokenUrl, cookie;

  public NestClient(String issueTokenUrl, String cookie) {
    this.issueTokenUrl = checkNotEmpty(normalize(issueTokenUrl));
    this.cookie = checkNotEmpty(normalize(cookie));
  }

  public void setTemperature(String thermostatSerialNumber, double degreesF) {
    Log.debug("Setting temperature of %s to %s", thermostatSerialNumber, degreesF);

    putThermostatData(thermostatSerialNumber, Json.object().with("target_temperature", NestThermostat.fToC(degreesF)));
  }

  public void setTemperature(String thermostatSerialNumber, ThermostatMode mode, double degreesF) {
    NestThermostat thermostat = (NestThermostat) getDevice(thermostatSerialNumber);
    if (thermostat.mode == mode) {
      setTemperature(thermostatSerialNumber, degreesF);
      return;
    }

    double previousTarget = thermostat.targetTemperature;
    setMode(thermostatSerialNumber, mode);

    for (int i = 30; i >= 0; i--) {
      thermostat = (NestThermostat) getDevice(thermostatSerialNumber);
      if (thermostat.targetTemperature != previousTarget) {
        break;
      }
      sleep(1000);
      if (i == 0) {
        Log.warn("Thermostat mode change not detected!");
      } else {
        Log.warn("mode not yet changed.");
        sleep(1000);
      }
    }

    setTemperature(thermostatSerialNumber, degreesF);

    // Json value = Json.object()
    // .with("target_temperature", Thermostat.fToC(degreesF))
    // .with("target_temperature_type", mode.getNestString());
    // putThermostatData(thermostatSerialNumber, value);
  }

  public void setMode(String thermostatSerialNumber, ThermostatMode mode) {
    Log.debug("Setting mode of %s to %s", thermostatSerialNumber, mode);

    putThermostatData(thermostatSerialNumber, Json.object().with("target_temperature_type", mode.getNestString()));
  }

  private void putThermostatData(String thermostatSerialNumber, Json value) {
    ensureValidToken();

    Json data = Json.object()
        .with("objects", Json.array(Json.object()
            .with("object_key", "shared." + thermostatSerialNumber)
            .with("op", "MERGE")
            .with("value", value)));

    Log.debug(data.prettyPrint());

    HttpRequest.post(transportUrl + "/v5/put")
        .chromeAgent()
        .authorization("Basic " + accessToken)
        .header("X-nl-protocol-version", "1")
        .send(data).checkStatus();
  }

  public Device getDevice(String serialNumber) {
    return only(filter(getDevices(), d -> Objects.equals(d.serialNumber, serialNumber)));
  }

  public List<Device> getDevices() {
    ensureValidToken();

    Map<String, String> roomNames = Maps.newLinkedHashMap();
    Json transformed = transformBuckets(getDevicesJson().getJson("updated_buckets").asJsonArray(), roomNames);

    List<Device> ret = Lists.newArrayList();
    for (String serialNumber : transformed) {
      NestThermostat thermostat = new NestThermostat(serialNumber);
      Json json = transformed.getJson(serialNumber);
      Json deviceJson = json.getJson("device");
      deviceJson.with("where_name", roomNames.get(deviceJson.get("where_id")));
      thermostat.update(json);
      ret.add(thermostat);
    }

    return ret;
  }

  private Json transformBuckets(List<Json> buckets, Map<String, String> roomNames) {
    Json transformed = Json.object();
    buckets.forEach(bucket -> {
      String objectKey = bucket.get("object_key");
      if (objectKey.startsWith("shared") || objectKey.startsWith("device")) {
        String type = first(objectKey, ".");
        String serialNumber = second(objectKey, ".");
        Json inner = transformed.getJson(serialNumber);
        if (inner == null) {
          transformed.with(serialNumber, inner = Json.object());
        }
        Json values = bucket.getJson("value");
        values.with("object_revision", bucket.getInt("object_revision"));
        values.with("object_timestamp", bucket.getLong("object_timestamp"));
        inner.with(type, values);
      } else if (objectKey.startsWith("where")) {
        bucket.getJson("value").getJson("wheres").asJsonArray().forEach(room -> {
          roomNames.put(room.get("where_id"), room.get("name"));
        });
      }
    });
    return transformed;
  }

  private Json getDevicesJson() {
    return HttpRequest.post("https://home.nest.com/api/0.1/user/" + userId + "/app_launch")
        .authorization("Basic " + accessToken)
        .send(Json.object()
            .with("known_bucket_types", Json.array("where", "device", "shared", "topaz"))
            .with("known_bucket_versions", Json.array()))
        .checkStatus().toJson();
  }

  /**
   * Listens for changes on the given devices. For example, when device B's target_temperature changes, the callback
   * will be invoked.
   */
  public void listenForChanges(List<Device> devices, Consumer<Device> callback) {
    final Map<String, Device> serialNumberDevices = index(devices, d -> d.serialNumber);

    Threads.run(() -> {
      while (true) {
        Json body = Json.object().with("objects", createBuckets(devices));
        Json response = HttpRequest.post(transportUrl + "/v5/subscribe")
            .chromeAgent()
            .authorization("Basic " + accessToken)
            .header("X-nl-user-id", this.userId)
            .header("X-nl-protocol-version", "1")
            .send(body).checkStatus().toJson();
        List<Json> objects = response.getJson("objects").asJsonArray();
        checkState(!objects.isEmpty());
        Json transformed = transformBuckets(objects, null);
        Log.debug("NestClient: Got thermostat changes from /subscribe");
        for (String key : transformed) {
          Device device = serialNumberDevices.get(key);
          device.update(transformed.getJson(key));
          callback.accept(device);
        }
      }
    });
  }

  private Json createBuckets(List<Device> devices) {
    Json buckets = Json.array();
    devices.forEach(d -> {
      buckets.add(Json.object()
          .with("object_key", "device." + d.serialNumber)
          .with("object_revision", d.deviceRevisionNumber)
          .with("object_timestamp", d.deviceTimestamp.toEpochMilli()));
      buckets.add(Json.object()
          .with("object_key", "shared." + d.serialNumber)
          .with("object_revision", d.sharedRevisionNumber)
          .with("object_timestamp", d.sharedTimestamp.toEpochMilli()));
    });
    return buckets;
  }

  private synchronized void ensureValidToken() {
    if (lastAccessTokenRefresh == null ||
        ChronoUnit.MINUTES.between(lastAccessTokenRefresh, Instant.now()) >= 59) {
      refreshAccessToken();
    }
    if (transportUrl == null) {
      transportUrl = getDevicesJson().getJson("service_urls").getJson("urls").get("transport_url");
    }
  }

  private synchronized void refreshAccessToken() {
    Log.info("NestClient: Refreshing access token.");

    Json info = getLoginInformation(getAccessToken(issueTokenUrl, cookie));
    userId = info.getJson("claims").getJson("subject").getJson("nestId").get("id");
    accessToken = info.get("jwt");
    lastAccessTokenRefresh = Instant.now();
  }

  private String getAccessToken(String issueTokenUrl, String cookie) {
    Json json = HttpRequest.get(issueTokenUrl)
        .chromeAgent()
        .header("Sec-Fetch-Mode", "cors")
        .header("X-Requested-With", "XmlHttpRequest")
        .header("Referer", "https://accounts.google.com/o/oauth2/iframe")
        .header("cookie", cookie).checkStatus().toJson();
    if (json.hasKey("error")) {
      json.log();
      throw new IllegalStateException(json.get("error"));
    }
    return json.get("access_token");
  }

  private Json getLoginInformation(String accessToken) {
    Map<String, Object> params = Maps.newLinkedHashMap();
    params.put("embed_google_oauth_access_token", "True");
    params.put("expire_after", "3600s");
    params.put("google_oauth_access_token", accessToken);
    params.put("policy_id", "authproxy-oauth-policy");

    final String NEST_KEY = "AIzaSyAdkSIMNc51XGNEAYWasX9UOWkS5P6sZE4";
    return HttpRequest.post("https://nestauthproxyservice-pa.googleapis.com/v1/issue_jwt", params)
        .chromeAgent()
        .header("Authorization", "Bearer " + accessToken)
        .header("x-goog-api-key", NEST_KEY)
        .header("Referer", "https://home.nest.com")
        .header(HttpRequest.HEADER_CONTENT_LENGTH, 0)
        .send("")
        .checkStatus().toJson();
  }


}

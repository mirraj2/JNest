package jnest;

import static ox.util.Utils.first;
import static ox.util.Utils.second;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import jnest.Thermostat.ThermostatMode;
import ox.HttpRequest;
import ox.Json;
import ox.Log;

public class NestClient {

  private String userId, accessToken;
  private String transportUrl;

  public NestClient login(String issueTokenUrl, String cookie) {
    Json info = getLoginInformation(getAccessToken(issueTokenUrl, cookie));
    // Log.debug(info.prettyPrint());
    userId = info.getJson("claims").getJson("subject").getJson("nestId").get("id");
    accessToken = info.get("jwt");
    Log.debug("NestClient: Logged in!");
    return this;
  }

  public void setTemperature(String thermostatSerialNumber, double degreesF) {
    Log.debug("Setting temperature of %s to %s", thermostatSerialNumber, degreesF);

    putThermostatData(thermostatSerialNumber, "target_temperature", Thermostat.fToC(degreesF));

  }

  public void setMode(String thermostatSerialNumber, ThermostatMode mode) {
    Log.debug("Setting mode of %s to %s", thermostatSerialNumber, mode);

    putThermostatData(thermostatSerialNumber, "target_temperature_type", mode.getNestString());
  }

  private void putThermostatData(String thermostatSerialNumber, String key, Object value) {
    Json data = Json.object()
        .with("objects", Json.array(Json.object()
            .with("object_key", "shared." + thermostatSerialNumber)
            .with("op", "MERGE")
            .with("value", Json.object().with(key, value))));

    Log.debug(data.prettyPrint());

    String s = HttpRequest.post(transportUrl + "/v5/put")
        .chromeAgent()
        .authorization("Basic " + accessToken)
        .header("X-nl-protocol-version", "1")
        .send(data).checkStatus().getBody();
    Log.debug(s);
  }

  public List<Device> getDevices() {
    Json json = HttpRequest.post("https://home.nest.com/api/0.1/user/" + userId + "/app_launch")
        .authorization("Basic " + accessToken)
        .send(Json.object()
            .with("known_bucket_types", Json.array("where", "device", "shared", "topaz"))
            .with("known_bucket_versions", Json.array()))
        .checkStatus().toJson();
    // Log.debug(json.prettyPrint());
    // Log.debug("========");

    transportUrl = json.getJson("service_urls").getJson("urls").get("transport_url");

    Map<String, String> roomNames = Maps.newLinkedHashMap();

    Json transformed = Json.object();
    json.getJson("updated_buckets").asJsonArray().forEach(bucket -> {
      String objectKey = bucket.get("object_key");
      if (objectKey.startsWith("shared") || objectKey.startsWith("device")) {
        String type = first(objectKey, ".");
        String serialNumber = second(objectKey, ".");
        Json inner = transformed.getJson(serialNumber);
        if (inner == null) {
          transformed.with(serialNumber, inner = Json.object());
        }
        inner.with(type, bucket.getJson("value"));
      } else if (objectKey.startsWith("where")) {
        bucket.getJson("value").getJson("wheres").asJsonArray().forEach(room -> {
          roomNames.put(room.get("where_id"), room.get("name"));
        });
      }
    });
    // Log.debug(transformed.prettyPrint());

    List<Device> ret = Lists.newArrayList();
    for (String serialNumber : transformed) {
      Thermostat thermostat = new Thermostat(serialNumber);
      thermostat.update(transformed.getJson(serialNumber), roomNames);
      ret.add(thermostat);
    }

    return ret;
  }

  private String getAccessToken(String issueTokenUrl, String cookie) {
    Json json = HttpRequest.get(issueTokenUrl)
        .chromeAgent()
        .header("Sec-Fetch-Mode", "cors")
        .header("X-Requested-With", "XmlHttpRequest")
        .header("Referer", "https://accounts.google.com/o/oauth2/iframe")
        .header("cookie", cookie).checkStatus().toJson();
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

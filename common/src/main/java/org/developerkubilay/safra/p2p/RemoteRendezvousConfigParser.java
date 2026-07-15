package org.developerkubilay.safra.p2p;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RemoteRendezvousConfigParser {
    private RemoteRendezvousConfigParser() {
    }

    public static String parseRemoteUrl(String body, String siteApiVersion, String target) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }

        JsonObject json = new JsonParser().parse(body).getAsJsonObject();
        return parseRemoteUrl(json, siteApiVersion, target);
    }

    public static String parseRemoteUrl(JsonObject json, String siteApiVersion, String target) {
        if (json == null) {
            return "";
        }

        JsonElement urlElement = json.get("api-test-only");
        if (urlElement == null || urlElement.isJsonNull()) {
            return "";
        }

        if (urlElement.isJsonPrimitive()) {
            return urlElement.getAsString();
        }

        if (!urlElement.isJsonObject()) {
            return "";
        }

        JsonObject endpoints = urlElement.getAsJsonObject();
        String resolved = endpointValue(endpoints, target);
        if (!resolved.trim().isEmpty()) {
            return resolved;
        }

        resolved = endpointValue(endpoints, "default");
        if (!resolved.trim().isEmpty()) {
            return resolved;
        }

        resolved = endpointValue(endpoints, "client");
        if (!resolved.trim().isEmpty()) {
            return resolved;
        }

        return endpointValue(endpoints, "dedicated");
    }

    private static String endpointValue(JsonObject endpoints, String key) {
        JsonElement endpoint = endpoints.get(key);
        if (endpoint == null || endpoint.isJsonNull() || !endpoint.isJsonPrimitive()) {
            return "";
        }

        return endpoint.getAsString();
    }
}

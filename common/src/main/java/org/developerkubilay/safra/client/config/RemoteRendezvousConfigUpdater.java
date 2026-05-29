package org.developerkubilay.safra.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRendezvousConfigUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteRendezvousConfigUpdater.class);
    private static final String REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/DeveloperKubilay/Safra/refs/heads/assets/config.json";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Request request = new Request.Builder()
            .url(REMOTE_CONFIG_URL)
            .get()
            .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOGGER.debug("Safra remote rendezvous config refresh skipped: {}", e.toString());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        applyRemoteConfig(config, body);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void applyRemoteConfig(BaseSafraClientConfig config, String body) {
        try {
            if (body == null || body.trim().isEmpty()) {
                return;
            }

            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            String key = "api-" + config.getSiteApiVersion();
            JsonElement urlElement = json.get(key);
            if (urlElement == null || urlElement.isJsonNull()) {
                return;
            }

            String remoteUrl = urlElement.getAsString();
            if (!P2pConstants.isValidRendezvousUrl(remoteUrl)) {
                config.setRendezvousUrl("");
                P2pConstants.setRuntimeRendezvousUrl(null);
                return;
            }

            config.setRendezvousUrl(remoteUrl);
            P2pConstants.setRuntimeRendezvousUrl(remoteUrl);
        } catch (RuntimeException exception) {
            LOGGER.debug("Safra remote rendezvous config could not be applied: {}", exception.toString());
        }
    }
}

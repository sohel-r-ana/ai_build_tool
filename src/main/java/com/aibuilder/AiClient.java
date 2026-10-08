package com.aibuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Talks to the GPT (OpenAI), Gemini and Claude (Anthropic) HTTP APIs. Pure Java. */
public final class AiClient {
    /** role is "user" or "assistant". */
    public record Msg(String role, String text) {
        public static Msg user(String t) { return new Msg("user", t); }
        public static Msg assistant(String t) { return new Msg("assistant", t); }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private AiClient() {}

    public static String complete(Config cfg, String provider, String system, List<Msg> msgs)
            throws IOException, InterruptedException {
        String key = cfg.key(provider);
        if (key == null) throw new IOException("No API key set for " + provider);
        String model = cfg.model(provider);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json");
        JsonObject body = new JsonObject();

        switch (provider) {
            case "gpt" -> {
                rb.uri(URI.create("https://api.openai.com/v1/chat/completions"))
                        .header("Authorization", "Bearer " + key);
                body.addProperty("model", model);
                body.addProperty("max_completion_tokens", cfg.maxTokens);
                JsonArray arr = new JsonArray();
                arr.add(obj("role", "system", "content", system));
                for (Msg m : msgs) arr.add(obj("role", m.role(), "content", m.text()));
                body.add("messages", arr);
            }
            case "claude" -> {
                rb.uri(URI.create("https://api.anthropic.com/v1/messages"))
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01");
                body.addProperty("model", model);
                body.addProperty("max_tokens", cfg.maxTokens);
                body.addProperty("system", system);
                JsonArray arr = new JsonArray();
                for (Msg m : msgs) arr.add(obj("role", m.role(), "content", m.text()));
                body.add("messages", arr);
            }
            case "gemini" -> {
                rb.uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                                + model + ":generateContent"))
                        .header("x-goog-api-key", key);
                JsonObject sys = new JsonObject();
                JsonArray sysParts = new JsonArray();
                sysParts.add(obj("text", system));
                sys.add("parts", sysParts);
                body.add("systemInstruction", sys);
                JsonArray contents = new JsonArray();
                for (Msg m : msgs) {
                    JsonObject c = new JsonObject();
                    c.addProperty("role", m.role().equals("assistant") ? "model" : "user");
                    JsonArray parts = new JsonArray();
                    parts.add(obj("text", m.text()));
                    c.add("parts", parts);
                    contents.add(c);
                }
                body.add("contents", contents);
                JsonObject gen = new JsonObject();
                gen.addProperty("maxOutputTokens", cfg.maxTokens);
                body.add("generationConfig", gen);
            }
            default -> throw new IOException("Unknown provider: " + provider);
        }

        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            String b = resp.body();
            if (b.length() > 400) b = b.substring(0, 400) + "...";
            throw new IOException(provider + " API error " + resp.statusCode() + ": " + b);
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        StringBuilder out = new StringBuilder();
        switch (provider) {
            case "gpt" -> {
                JsonElement c = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content");
                if (c != null && !c.isJsonNull()) out.append(c.getAsString());
            }
            case "claude" -> {
                for (JsonElement e : root.getAsJsonArray("content")) {
                    JsonObject o = e.getAsJsonObject();
                    if ("text".equals(o.get("type").getAsString())) out.append(o.get("text").getAsString());
                }
            }
            case "gemini" -> {
                JsonArray cands = root.getAsJsonArray("candidates");
                if (cands != null && !cands.isEmpty()) {
                    JsonObject content = cands.get(0).getAsJsonObject().getAsJsonObject("content");
                    if (content != null && content.has("parts")) {
                        for (JsonElement e : content.getAsJsonArray("parts")) {
                            JsonObject o = e.getAsJsonObject();
                            boolean thought = o.has("thought") && o.get("thought").getAsBoolean();
                            if (!thought && o.has("text")) out.append(o.get("text").getAsString());
                        }
                    }
                }
            }
            default -> { }
        }
        if (out.length() == 0) throw new IOException(provider + " returned an empty answer (blocked or cut off?)");
        return out.toString();
    }

    private static JsonObject obj(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }
}

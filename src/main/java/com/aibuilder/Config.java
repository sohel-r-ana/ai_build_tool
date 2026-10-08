package com.aibuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Settings stored in config/ai-builder/config.json. Pure Java (no Minecraft classes). */
public class Config {
    /** Active provider: "gpt", "gemini" or "claude". */
    public String provider = "claude";
    /** API keys per provider. Stored on the machine that runs the server (or singleplayer world). */
    public Map<String, String> keys = new HashMap<>();
    /** Model overrides per provider (see DEFAULT_MODELS). Change with ./model. */
    public Map<String, String> models = new HashMap<>();
    /** Max tokens the AI may write per answer. Big builds need a lot. */
    public int maxTokens = 16000;
    /** How many extra review/fix passes the AI does after writing the plan. 0 = off. */
    public int refinePasses = 1;
    /** Blocks placed per server tick (lower = less lag, slower build). */
    public int blocksPerTick = 3000;
    /** Hard cap on blocks in a single build. */
    public int maxBlocks = 250000;
    /** HTTP timeout for one AI request. */
    public int timeoutSeconds = 600;
    /** If true only creative-mode players may use the commands (unless allowedPlayers is set). */
    public boolean requireCreative = true;
    /** If not empty, ONLY these player names may use the commands. */
    public List<String> allowedPlayers = new ArrayList<>();

    private transient Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final Map<String, String> DEFAULT_MODELS = Map.of(
            "gpt", "gpt-5",
            "gemini", "gemini-2.5-pro",
            "claude", "claude-sonnet-5-5");

    public static Config load(Path dir) {
        Config c = null;
        Path f = dir.resolve("config.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(f)) {
                c = GSON.fromJson(Files.readString(f), Config.class);
            }
        } catch (Exception e) {
            System.err.println("[AI Builder] Could not read config, using defaults: " + e);
        }
        if (c == null) c = new Config();
        if (c.keys == null) c.keys = new HashMap<>();
        if (c.models == null) c.models = new HashMap<>();
        if (c.allowedPlayers == null) c.allowedPlayers = new ArrayList<>();
        c.file = f;
        c.save();
        return c;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[AI Builder] Could not save config: " + e);
        }
    }

    public String model(String p) {
        String m = models.get(p);
        return (m == null || m.isBlank()) ? DEFAULT_MODELS.get(p) : m;
    }

    public String key(String p) {
        String k = keys.get(p);
        return (k == null || k.isBlank()) ? null : k.trim();
    }
}

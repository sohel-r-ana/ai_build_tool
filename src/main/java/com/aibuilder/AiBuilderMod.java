package com.aibuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public class AiBuilderMod implements ModInitializer {
    private static final String PREFIX = "\u00a76[AI Builder] \u00a7r";

    /** Canonical command names (used for both ./name in chat and /name as a real command). */
    private static final List<String> COMMANDS = List.of(
            "build", "keygpt", "keygemini", "keyclaude", "buildprovider", "buildmodel",
            "buildundo", "buildcancel", "buildstatus", "savebuild", "loadbuild", "listbuilds", "buildhelp");

    /** Short chat aliases -> canonical name. */
    private static final Map<String, String> ALIASES = Map.of(
            "provider", "buildprovider", "model", "buildmodel", "undo", "buildundo",
            "cancel", "buildcancel", "status", "buildstatus", "help", "buildhelp");

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private static volatile MinecraftServer SERVER;
    private static Config CFG;
    private static Path DIR;
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-builder-worker");
        t.setDaemon(true);
        return t;
    });

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final List<Placer.Job> JOBS = new ArrayList<>();
    private static final List<Check> CHECKS = new ArrayList<>();

    private static final class Session {
        volatile int gen;
        volatile boolean generating;
        Future<?> task;
        BuildPlan plan;      // ready to be placed
        BuildPlan last;      // last placed / generated (for ./savebuild)
        BlockPos pendingPos; // structure block placed while the AI was still working
        ServerLevel pendingLevel;
        Placer.Job job;
        Map<BlockPos, BlockState> undo;
        ServerLevel undoLevel;
    }

    private static final class Check {
        final UUID id;
        final ServerLevel level;
        final List<BlockPos> cands;
        int ticks = 2;

        Check(UUID id, ServerLevel level, List<BlockPos> cands) {
            this.id = id;
            this.level = level;
            this.cands = cands;
        }
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void onInitialize() {
        DIR = FabricLoader.getInstance().getConfigDir().resolve("ai-builder");
        CFG = Config.load(DIR);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVER = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SESSIONS.values().forEach(s -> { if (s.task != null) s.task.cancel(true); });
            SESSIONS.clear();
            JOBS.clear();
            CHECKS.clear();
        });

        // Typing "./something" in chat. Handled commands are NOT shown to other players.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String raw = message.signedContent();
            if (raw == null) return true;
            raw = raw.trim();
            if (!raw.startsWith("./")) return true;
            String body = raw.substring(2).trim();
            int sp = body.indexOf(' ');
            String cmd = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
            String args = sp < 0 ? "" : body.substring(sp + 1).trim();
            cmd = ALIASES.getOrDefault(cmd, cmd);
            if (!COMMANDS.contains(cmd)) return true; // not ours, let it through as normal chat
            dispatch(sender, cmd, args);
            return false;
        });

        // Same commands as real /commands too (/build, /keyclaude ...).
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String name : COMMANDS) {
                dispatcher.register(Commands.literal(name)
                        .executes(ctx -> run(ctx.getSource(), name, ""))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource(), name,
                                        StringArgumentType.getString(ctx, "text")))));
            }
        });

        // Detect a structure block being placed.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            if (!player.getItemInHand(hand).is(Items.STRUCTURE_BLOCK)) return InteractionResult.PASS;
            Session s = SESSIONS.get(sp.getUUID());
            if (s == null || (s.plan == null && !s.generating)) return InteractionResult.PASS;
            if (!allowed(sp)) return InteractionResult.PASS;
            List<BlockPos> cands = new ArrayList<>();
            BlockPos clicked = hit.getBlockPos();
            BlockPos rel = clicked.relative(hit.getDirection());
            if (!sl.getBlockState(clicked).is(Blocks.STRUCTURE_BLOCK)) cands.add(clicked);
            if (!sl.getBlockState(rel).is(Blocks.STRUCTURE_BLOCK)) cands.add(rel);
            CHECKS.add(new Check(sp.getUUID(), sl, cands));
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            JOBS.removeIf(j -> j.step(CFG.blocksPerTick));
            List<Check> due = new ArrayList<>();
            CHECKS.removeIf(c -> {
                if (--c.ticks > 0) return false;
                due.add(c);
                return true;
            });
            for (Check c : due) {
                ServerPlayer p = server.getPlayerList().getPlayer(c.id);
                if (p == null) continue;
                for (BlockPos pos : c.cands) {
                    if (c.level.getBlockState(pos).is(Blocks.STRUCTURE_BLOCK)) {
                        trigger(p, c.level, pos);
                        break;
                    }
                }
            }
        });
    }

    private static int run(CommandSourceStack src, String name, String args) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("AI Builder commands can only be used by players."));
            return 0;
        }
        dispatch(p, name, args);
        return 1;
    }

    // --------------------------------------------------------------- commands

    private static boolean allowed(ServerPlayer p) {
        String n = p.getName().getString();
        if (!CFG.allowedPlayers.isEmpty()) return CFG.allowedPlayers.stream().anyMatch(n::equalsIgnoreCase);
        return !CFG.requireCreative || p.isCreative();
    }

    private static void dispatch(ServerPlayer p, String cmd, String args) {
        if (!allowed(p)) {
            msg(p, "\u00a7cYou are not allowed to use AI Builder (creative mode is required, or be listed in "
                    + "allowedPlayers in config/ai-builder/config.json).");
            return;
        }
        switch (cmd) {
            case "build" -> build(p, args);
            case "keygpt" -> setKey(p, "gpt", "GPT", args);
            case "keygemini" -> setKey(p, "gemini", "Gemini", args);
            case "keyclaude" -> setKey(p, "claude", "Claude", args);
            case "buildprovider" -> setProvider(p, args);
            case "buildmodel" -> setModel(p, args);
            case "buildundo" -> undo(p);
            case "buildcancel" -> cancel(p);
            case "buildstatus" -> status(p);
            case "savebuild" -> saveBuild(p, args);
            case "loadbuild" -> loadBuild(p, args);
            case "listbuilds" -> listBuilds(p);
            default -> help(p);
        }
    }

    private static void help(ServerPlayer p) {
        msg(p, "Commands (type in chat as ./name, or use /name):");
        msg(p, "\u00a7e./build <prompt>\u00a7r - AI designs it; then place a structure block where the bottom center goes");
        msg(p, "\u00a7e./keygpt \"key\"\u00a7r, \u00a7e./keygemini \"key\"\u00a7r, \u00a7e./keyclaude \"key\"\u00a7r - set key & use that AI");
        msg(p, "\u00a7e./provider <gpt|gemini|claude>\u00a7r, \u00a7e./model [provider] <model name>\u00a7r");
        msg(p, "\u00a7e./undo\u00a7r, \u00a7e./cancel\u00a7r, \u00a7e./status\u00a7r");
        msg(p, "\u00a7e./savebuild <name>\u00a7r, \u00a7e./loadbuild <name>\u00a7r, \u00a7e./listbuilds\u00a7r - re-use builds for free");
    }

    private static void setKey(ServerPlayer p, String provider, String label, String args) {
        String k = args.trim();
        if (k.length() >= 2 && ((k.startsWith("\"") && k.endsWith("\"")) || (k.startsWith("'") && k.endsWith("'")))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        if (k.isEmpty()) {
            msg(p, "Usage: ./key" + provider + " \"your api key\"");
            return;
        }
        CFG.keys.put(provider, k);
        CFG.provider = provider;
        CFG.save();
        msg(p, label + " key saved. Now using " + label + " (model " + CFG.model(provider) + ").");
    }

    private static void setProvider(ServerPlayer p, String args) {
        String a = args.trim().toLowerCase();
        if (!Config.DEFAULT_MODELS.containsKey(a)) {
            msg(p, "Usage: ./provider <gpt|gemini|claude>");
            return;
        }
        CFG.provider = a;
        CFG.save();
        msg(p, "Now using " + a + " (model " + CFG.model(a) + ")" + (CFG.key(a) == null ? " - but no key is set yet!" : "."));
    }

    private static void setModel(ServerPlayer p, String args) {
        String[] t = args.trim().split("\\s+");
        String provider = CFG.provider;
        String model;
        if (t.length >= 2 && Config.DEFAULT_MODELS.containsKey(t[0].toLowerCase())) {
            provider = t[0].toLowerCase();
            model = t[1];
        } else if (t.length >= 1 && !t[0].isEmpty()) {
            model = t[0];
        } else {
            msg(p, "Usage: ./model [gpt|gemini|claude] <model name>   (current: " + CFG.model(provider) + ")");
            return;
        }
        CFG.models.put(provider, model);
        CFG.save();
        msg(p, "Model for " + provider + " set to " + model + ".");
    }

    private static void status(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        msg(p, "Provider: " + CFG.provider + " | model: " + CFG.model(CFG.provider));
        msg(p, "Keys set: GPT=" + yn(CFG.key("gpt")) + " Gemini=" + yn(CFG.key("gemini")) + " Claude=" + yn(CFG.key("claude")));
        msg(p, "Review passes: " + CFG.refinePasses + " | blocks/tick: " + CFG.blocksPerTick + " | max blocks: " + CFG.maxBlocks);
        if (s == null) msg(p, "No build in progress.");
        else if (s.generating) msg(p, "The AI is working on your build...");
        else if (s.plan != null) msg(p, "Build ready: " + s.plan.name + " " + s.plan.sizeText() + " - place a structure block.");
        else msg(p, "No build ready. Use ./build <prompt>.");
    }

    private static String yn(String k) { return k == null ? "no" : "yes"; }

    // ------------------------------------------------------------------ build

    private static Session session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), k -> new Session());
    }

    private static void build(ServerPlayer p, String prompt) {
        prompt = prompt.trim();
        if (prompt.isEmpty()) {
            msg(p, "Usage: ./build <what to build>   e.g. ./build a 50 block tall and 11x11 block wide watch tower");
            return;
        }
        final String provider = CFG.provider;
        if (CFG.key(provider) == null) {
            msg(p, "\u00a7cNo API key for " + provider + ". Use ./keygpt, ./keygemini or ./keyclaude first.");
            return;
        }
        Session s = session(p);
        if (s.generating) {
            msg(p, "Already working on a build. Use ./cancel to stop it.");
            return;
        }
        if (s.job != null) {
            msg(p, "Still placing the previous build, wait a moment.");
            return;
        }
        final int gen = ++s.gen;
        s.generating = true;
        s.plan = null;
        s.pendingPos = null;
        final UUID id = p.getUUID();
        final String req = prompt;
        final Config cfg = CFG;
        msg(p, "Working on it with " + provider + " (" + cfg.model(provider) + "). This can take a few minutes for big builds.");
        s.task = EXEC.submit(() -> {
            try {
                BuildPlan plan = BuildPipeline.generate(cfg, provider, req, line -> {
                    if (s.gen == gen) msgLater(id, line);
                });
                SERVER.execute(() -> finishBuild(id, s, gen, plan, null));
            } catch (InterruptedException ie) {
                // cancelled
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage());
                SERVER.execute(() -> finishBuild(id, s, gen, null, m));
            }
        });
    }

    private static void finishBuild(UUID id, Session s, int gen, BuildPlan plan, String error) {
        if (s.gen != gen) return; // cancelled / replaced
        s.generating = false;
        ServerPlayer p = SERVER.getPlayerList().getPlayer(id);
        if (error != null || plan == null) {
            s.pendingPos = null;
            if (p != null) msg(p, "\u00a7cBuild failed: " + error);
            return;
        }
        s.plan = plan;
        s.last = plan;
        if (p == null) return;
        msg(p, "\u00a7aBuild ready: \"" + plan.name + "\" " + plan.sizeText() + ", " + plan.blocks.size() + " blocks.");
        if (s.pendingPos != null && s.pendingLevel != null
                && s.pendingLevel.getBlockState(s.pendingPos).is(Blocks.STRUCTURE_BLOCK)) {
            place(p, s, s.pendingLevel, s.pendingPos);
        } else {
            msg(p, "Place a structure block where the bottom center of the build should be.");
        }
    }

    private static void cancel(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null) {
            msg(p, "Nothing to cancel.");
            return;
        }
        s.gen++;
        s.generating = false;
        s.plan = null;
        s.pendingPos = null;
        if (s.task != null) s.task.cancel(true);
        msg(p, "Cancelled. (A build that is already being placed cannot be stopped - use ./undo.)");
    }

    // -------------------------------------------------------------- placement

    private static void trigger(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Session s = session(p);
        if (s.plan != null) {
            place(p, s, level, pos);
        } else if (s.generating) {
            s.pendingPos = pos;
            s.pendingLevel = level;
            msg(p, "The AI is still working - the build will be placed here as soon as it is ready.");
        }
    }

    private static void place(ServerPlayer p, Session s, ServerLevel level, BlockPos origin) {
        if (s.job != null) {
            msg(p, "Still placing the previous build, wait a moment.");
            return;
        }
        BuildPlan plan = s.plan;
        if (plan == null) return;
        level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); // remove the structure block
        Set<String> unknown = new LinkedHashSet<>();
        List<Placer.E> list = Placer.prepare(plan, origin, level, unknown);
        s.undo = new LinkedHashMap<>();
        s.undoLevel = level;
        s.plan = null;
        s.pendingPos = null;
        final UUID id = p.getUUID();
        final int total = list.size();
        s.job = new Placer.Job(level, list, s.undo, () -> {
            s.job = null;
            msgLater(id, "\u00a7aDone! Placed " + total + " blocks. Use ./undo to revert.");
            if (!unknown.isEmpty()) {
                msgLater(id, "\u00a7eSkipped unknown blocks: " + String.join(", ", unknown));
            }
        });
        JOBS.add(s.job);
        msg(p, "Placing " + total + " blocks...");
    }

    private static void undo(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.undo == null || s.undo.isEmpty() || s.undoLevel == null) {
            msg(p, "Nothing to undo.");
            return;
        }
        if (s.job != null) {
            msg(p, "Still placing, wait for it to finish first.");
            return;
        }
        List<Placer.E> list = new ArrayList<>();
        s.undo.forEach((pos, st) -> list.add(new Placer.E(pos, st)));
        s.undo = null;
        final UUID id = p.getUUID();
        s.job = new Placer.Job(s.undoLevel, list, null, () -> {
            s.job = null;
            msgLater(id, "\u00a7aUndone.");
        });
        JOBS.add(s.job);
        msg(p, "Undoing " + list.size() + " blocks...");
    }

    // ------------------------------------------------------------- save / load

    private static String cleanName(String n) {
        return n.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static void saveBuild(ServerPlayer p, String args) {
        Session s = SESSIONS.get(p.getUUID());
        BuildPlan plan = s == null ? null : (s.plan != null ? s.plan : s.last);
        if (plan == null) {
            msg(p, "There is no build to save yet.");
            return;
        }
        String name = cleanName(args.isBlank() ? plan.name : args);
        try {
            Path dir = DIR.resolve("builds");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".json"), PRETTY.toJson(plan.source));
            msg(p, "Saved as \"" + name + "\". Load it later with ./loadbuild " + name + " (no AI cost).");
        } catch (Exception e) {
            msg(p, "\u00a7cCould not save: " + e.getMessage());
        }
    }

    private static void loadBuild(ServerPlayer p, String args) {
        if (args.isBlank()) {
            msg(p, "Usage: ./loadbuild <name>   (see ./listbuilds)");
            return;
        }
        Session s = session(p);
        try {
            Path f = DIR.resolve("builds").resolve(cleanName(args) + ".json");
            if (!Files.exists(f)) {
                msg(p, "\u00a7cNo saved build with that name.");
                return;
            }
            BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(Files.readString(f)).getAsJsonObject(), CFG.maxBlocks);
            s.plan = plan;
            s.last = plan;
            msg(p, "\u00a7aLoaded \"" + plan.name + "\" " + plan.sizeText() + ". Place a structure block.");
        } catch (Exception e) {
            msg(p, "\u00a7cCould not load: " + e.getMessage());
        }
    }

    private static void listBuilds(ServerPlayer p) {
        Path dir = DIR.resolve("builds");
        if (!Files.isDirectory(dir)) {
            msg(p, "No saved builds yet.");
            return;
        }
        try (Stream<Path> st = Files.list(dir)) {
            List<String> names = st.map(x -> x.getFileName().toString())
                    .filter(x -> x.endsWith(".json"))
                    .map(x -> x.substring(0, x.length() - 5)).sorted().toList();
            msg(p, names.isEmpty() ? "No saved builds yet." : "Saved builds: " + String.join(", ", names));
        } catch (Exception e) {
            msg(p, "\u00a7cCould not list builds: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- messages

    private static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(PREFIX + text));
    }

    /** Safe to call from any thread. */
    private static void msgLater(UUID id, String text) {
        MinecraftServer srv = SERVER;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = srv.getPlayerList().getPlayer(id);
            if (p != null) msg(p, text);
        });
    }
}

package com.aibuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "brain": imagine -> analyze every detail -> write block program (JSON) -> review/fix.
 * Pure Java. Runs on a background thread.
 */
public final class BuildPipeline {
    private BuildPipeline() {}

    private static String system(Config cfg) {
        return """
                You are a world-class Minecraft Java Edition architect and builder. You design \
                structures with real architectural thinking (proportions, silhouette, layered \
                materials, depth, trim, lighting, believable interiors) and then convert them into \
                exact block-placement programs. You only use real Minecraft Java block ids and valid \
                block-state properties. Build limit: at most %d blocks in total.
                """.formatted(cfg.maxBlocks);
    }

    private static final String COORDS = """
            COORDINATE SYSTEM: [x,y,z] are integers relative to the build origin. x and z are measured \
            from the CENTER column of the build; y=0 is the bottom layer (the layer the player stands the \
            structure block on), y grows upward. For an ODD width W the x range is -(W-1)/2..(W-1)/2 \
            (11 wide = -5..5). For an EVEN width W the range is -W/2..W/2-1. Same for z (depth). \
            Orientation: north = -z, south = +z, east = +x, west = -x.
            """;

    private static final String SPEC = """
            OUTPUT FORMAT - one single JSON object, nothing else (no commentary, no markdown fences):
            {
              "name": "short_name",
              "palette": { "w": "minecraft:stone_bricks", "s": "minecraft:stone_brick_stairs[facing=north,half=bottom]" },
              "ops": [ ... ]
            }
            Ops run in order; later ops overwrite earlier ones at the same position. The "block" field is a \
            palette key or a full block id. "air" clears/carves blocks. "keep" leaves the world untouched.
            Available ops:
            {"op":"box","from":[x,y,z],"to":[x,y,z],"block":"w"}                solid box
            {"op":"hollow","from":[x,y,z],"to":[x,y,z],"block":"w","t":1}      box shell (6 faces), thickness t
            {"op":"walls","from":[x,y,z],"to":[x,y,z],"block":"w","t":1}       only the 4 vertical sides
            {"op":"line","from":[x,y,z],"to":[x,y,z],"block":"w"}
            {"op":"cylinder","center":[x,y,z],"radius":5,"height":10,"block":"w","hollow":true}  y = bottom layer
            {"op":"sphere","center":[x,y,z],"radius":5,"block":"w","hollow":true,"half":"top"}   half: top|bottom|full
            {"op":"pyramid","center":[x,y,z],"size":6,"block":"w","hollow":false}  stepped pyramid, y = bottom layer, \
            base is 2*size+1 wide, shrinks by 1 each layer (good for roofs)
            {"op":"block","pos":[x,y,z],"block":"s"}                              one block
            {"op":"blocks","list":[[x,y,z],[x,y,z]],"block":"w"}                  many single blocks, same type
            RULES:
            - Use big primitives for large surfaces and single blocks for every small detail from your analysis.
            - Carve interiors with "air" BEFORE placing furniture/stairs/ladders inside them.
            - Block-state notes: stairs facing = the direction you walk UP (side of the tall back); \
            use half=bottom/top and shape when needed; slabs type=bottom/top; doors need two ops (half=lower and \
            half=upper) with facing/hinge/open; ladders/wall torches/wall signs/trapdoors need facing; \
            fences, walls and glass panes connect automatically.
            - Only block ids that exist in Minecraft Java 26.x. No NBT, no command blocks, no chest contents.
            - Every block must be supported (no floating parts) unless it is meant to float.
            - The result must be rich and detailed (trim, windows, depth, lighting, roof shape), never a plain box.
            """;

    public static BuildPlan generate(Config cfg, String provider, String request, Consumer<String> progress)
            throws Exception {
        String sys = system(cfg);
        List<AiClient.Msg> h = new ArrayList<>();

        // Stage 1 - imagine
        progress.accept("Stage 1/3: imagining the build...");
        h.add(AiClient.Msg.user("BUILD REQUEST: " + request + "\n\n" + COORDS + """

                STAGE 1 - IMAGINE. Picture the finished build like a professional architect. Describe it \
                concretely: overall silhouette, exact overall dimensions (width x depth x height in blocks - \
                if the request gives dimensions they are MANDATORY and must be matched exactly), style, \
                the block palette (real Minecraft block ids), levels/floors and their heights, roof, \
                entrances, windows, the interior (stairs, ladders, furniture, lighting), and decorations. \
                Do not write JSON yet."""));
        String design = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(design));

        // Stage 2 - analyze
        progress.accept("Stage 2/3: analyzing every detail...");
        h.add(AiClient.Msg.user("""
                STAGE 2 - ANALYZE. Now analyze your design in EXTREME detail and break it down into the \
                smallest parts and shapes possible. For every part (foundation, each wall, each floor, \
                every window, door, stair, ladder, battlement, roof layer, pillar, buttress, banner, torch, \
                lantern, trim line, corner, ...) write the exact coordinates / ranges and the exact block \
                with block-state properties. Then VERIFY: overall size matches the request exactly; every \
                block is supported; stair/door/ladder/trapdoor orientations are right; interiors are \
                walkable with 2 blocks of headroom; entrances are reachable; it stays under the block limit. \
                Fix any problem you find and give the corrected final breakdown."""));
        String analysis = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(analysis));

        // Stage 3 - generate JSON
        progress.accept("Stage 3/3: writing the block plan...");
        h.add(AiClient.Msg.user("STAGE 3 - GENERATE. Convert your final breakdown into the block program. "
                + "Include EVERY detail from your analysis.\n\n" + COORDS + "\n" + SPEC));
        String out = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(out));

        BuildPlan plan = null;
        Exception last = null;
        for (int attempt = 0; attempt < 4 && plan == null; attempt++) {
            try {
                plan = parse(out, cfg);
            } catch (Exception e) {
                last = e;
                if (attempt == 3) break;
                progress.accept("Plan had a problem (" + shorten(e.getMessage()) + "), asking the AI to fix it...");
                h.add(AiClient.Msg.user("Your JSON could not be used: " + e.getMessage()
                        + "\nOutput ONLY the complete corrected JSON object."));
                out = AiClient.complete(cfg, provider, sys, h);
                h.add(AiClient.Msg.assistant(out));
            }
        }
        if (plan == null) {
            throw new IOException("The AI produced an invalid plan: " + (last == null ? "?" : shorten(last.getMessage())));
        }

        // Review passes
        for (int i = 1; i <= cfg.refinePasses; i++) {
            progress.accept("Reviewing the build (pass " + i + "/" + cfg.refinePasses + ")...");
            List<AiClient.Msg> h2 = new ArrayList<>(h);
            h2.add(AiClient.Msg.user("""
                    STAGE 4 - REVIEW. Carefully check the JSON you just wrote against your analysis and the \
                    original request. Check: exact requested dimensions (compute the min/max of every axis \
                    that your ops touch), floating blocks, stair/door/ladder/trapdoor orientations, missing \
                    details, doorway clearance, interior lighting, interiors carved with air before \
                    furnishing, invalid block ids or properties, and anything that looks thin or plain. \
                    Output ONLY the complete corrected JSON object (same format) - improve the detail where \
                    it is weak."""));
            try {
                String r = AiClient.complete(cfg, provider, sys, h2);
                BuildPlan p2 = parse(r, cfg);
                plan = p2;
                h2.add(AiClient.Msg.assistant(r));
                h = h2;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                progress.accept("Review pass failed, keeping the previous plan.");
                break;
            }
        }
        return plan;
    }

    static BuildPlan parse(String text, Config cfg) {
        String t = text.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON object found in the answer");
        JsonObject root;
        try {
            root = JsonParser.parseString(t.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON (maybe cut off - too long?): " + e.getMessage());
        }
        return BuildPlan.fromJson(root, cfg.maxBlocks);
    }

    private static String shorten(String s) {
        if (s == null) return "?";
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}

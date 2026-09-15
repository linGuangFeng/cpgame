package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.StepFact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 训练 kernel 与 Redis ASCII member 共用的 Step 事实编解码。 */
public final class KernelCodec {
    public static final String PREFIX = "SACIA1";

    private KernelCodec() {}

    public static String encodeSteps(List<StepFact> steps) {
        List<String> encoded = new ArrayList<>(steps.size());
        for (StepFact step : steps) encoded.add(encodeStep(step));
        return String.join("|", encoded);
    }

    public static List<StepFact> decodeSteps(String payload) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("空 kernel");
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("member 必须是 US-ASCII");
        }
        String[] parts = payload.split("\\|", -1);
        List<StepFact> steps = new ArrayList<>(parts.length);
        for (String part : parts) steps.add(decodeStep(part));
        return steps;
    }

    public static RoundCandidate decodeCandidate(String line) {
        int sep = line.indexOf(';');
        if (sep <= 0) throw new IllegalArgumentException("kernel 缺少 mode");
        RoundMode mode = RoundMode.valueOf(line.substring(0, sep));
        return new RoundCandidate(mode, decodeSteps(line.substring(sep + 1)));
    }

    public static String encodeKernel(RoundCandidate candidate) {
        return candidate.mode().name() + ";" + encodeSteps(candidate.steps());
    }

    static String encodeStep(StepFact step) {
        return String.join("~",
                String.join(",", step.rskl()),
                joinInts(step.syxl()),
                joinInts(step.wskl()),
                joinAfnl(step.afnl()),
                Integer.toString(step.smallGameType()),
                Integer.toString(step.ss()),
                Integer.toString(step.fsn()),
                Integer.toString(step.nfsc()),
                Integer.toString(step.rsn()),
                Integer.toString(step.nrsc()),
                Integer.toString(step.gt()),
                Integer.toString(step.gm()),
                Integer.toString(step.wn()));
    }

    static StepFact decodeStep(String raw) {
        String[] f = raw.split("~", -1);
        if (f.length != 13) throw new IllegalArgumentException("step 字段数错误: " + f.length);
        return new StepFact(
                List.of(f[0].split(",", -1)),
                parseInts(f[1]),
                parseInts(f[2]),
                parseAfnl(f[3]),
                Integer.parseInt(f[4]),
                Integer.parseInt(f[5]),
                Integer.parseInt(f[6]),
                Integer.parseInt(f[7]),
                Integer.parseInt(f[8]),
                Integer.parseInt(f[9]),
                Integer.parseInt(f[10]),
                Integer.parseInt(f[11]),
                Integer.parseInt(f[12]));
    }

    private static String joinInts(List<Integer> values) {
        if (values.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append('.');
            b.append(values.get(i));
        }
        return b.toString();
    }

    private static List<Integer> parseInts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("\\.", -1);
        List<Integer> values = new ArrayList<>(parts.length);
        for (String part : parts) values.add(Integer.parseInt(part));
        return values;
    }

    private static String joinAfnl(List<List<Integer>> values) {
        if (values.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append('.');
            List<Integer> row = values.get(i);
            b.append(row.get(0)).append('-').append(row.get(1));
        }
        return b.toString();
    }

    private static List<List<Integer>> parseAfnl(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split("\\.", -1);
        List<List<Integer>> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String[] pair = part.split("-", -1);
            values.add(List.of(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
        }
        return values;
    }
}

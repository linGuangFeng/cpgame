package com.cpgame.hiddenrealm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** HR1 stores only boards and delivery counters. ASCII, never JSON. */
public final class RoundCodec {
    public String encode(CompleteRound round) {
        StringJoiner deliveries = new StringJoiner(";", "HR1|", "");
        for (CompleteRound.Delivery delivery : round.deliveries()) {
            StringJoiner pages = new StringJoiner(".");
            for (CompleteRound.Page page : delivery.pages()) pages.add(board(page.board()));
            deliveries.add(delivery.type() + "," + delivery.typeSkill() + "," + delivery.maxPhase() + ","
                    + delivery.collection() + "," + delivery.smallGameType() + ":" + pages);
        }
        return deliveries.toString();
    }

    public CompleteRound decode(String member) {
        if (member == null || !member.startsWith("HR1|")) throw new IllegalArgumentException("HR1 member required");
        List<CompleteRound.Delivery> deliveries = new ArrayList<>();
        String body = member.substring(4);
        if (body.isEmpty()) throw new IllegalArgumentException("empty member");
        for (String raw : body.split(";")) {
            int colon = raw.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("delivery");
            String[] head = raw.substring(0, colon).split(",");
            if (head.length != 5) throw new IllegalArgumentException("delivery head");
            List<CompleteRound.Page> pages = new ArrayList<>();
            for (String page : raw.substring(colon + 1).split("\\.")) pages.add(new CompleteRound.Page(parse(page)));
            deliveries.add(new CompleteRound.Delivery(
                    Integer.parseInt(head[0]), Integer.parseInt(head[1]), Integer.parseInt(head[2]),
                    Integer.parseInt(head[3]), Integer.parseInt(head[4]), pages));
        }
        return new CompleteRound(deliveries);
    }

    private String board(int[][] b) {
        char[] chars = new char[25];
        int i = 0;
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++) chars[i++] = (char) ('0' + b[c][r]);
        return new String(chars);
    }

    private int[][] parse(String raw) {
        if (raw.length() != 25) throw new IllegalArgumentException("board width");
        int[][] b = new int[5][5];
        int i = 0;
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++) {
            int s = raw.charAt(i++) - '0';
            if (s < 1 || s > 9) throw new IllegalArgumentException("symbol");
            b[c][r] = s;
        }
        return b;
    }
}

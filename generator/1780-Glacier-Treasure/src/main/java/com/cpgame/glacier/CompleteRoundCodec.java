package com.cpgame.glacier;

import java.util.ArrayList;
import java.util.List;

import static com.cpgame.glacier.GameRuleCore.Board;
import static com.cpgame.glacier.GameRuleCore.Symbol;

/**
 * Minimal ASCII member. Restores visible prop/grid/frame; ids are reconstructed on decode.
 * Does not start with '{' or '['.
 */
public final class CompleteRoundCodec {
    public String encode(CompleteRoundFact fact) {
        StringBuilder out = new StringBuilder();
        for (int i=0;i<fact.spins().size();i++) {
            if (i>0) out.append('|');
            List<Board> pages = fact.spins().get(i);
            for (int p=0;p<pages.size();p++) {
                if (p>0) out.append('+');
                appendBoard(out, pages.get(p));
            }
        }
        String s = out.toString();
        if (s.isEmpty() || s.charAt(0)=='{' || s.charAt(0)=='[')
            throw new IllegalStateException("illegal compact member");
        return s;
    }

    public CompleteRoundFact decode(String value) {
        return decode(value, false);
    }

    public CompleteRoundFact decode(String value, boolean featureBuy) {
        if (value==null || value.isBlank()) throw new IllegalArgumentException("empty compact round");
        String trimmed=value.trim();
        if (trimmed.charAt(0)=='{' || trimmed.charAt(0)=='[')
            throw new IllegalArgumentException("JSON members are not allowed");
        String[] spinParts = trimmed.split("\\|", -1);
        var spins = new ArrayList<List<Board>>();
        for (String spin : spinParts) {
            if (spin.isEmpty()) throw new IllegalArgumentException("empty spin");
            String[] pages = spin.split("\\+", -1);
            var list = new ArrayList<Board>();
            int nextId=1;
            Board prev=null;
            var core=new GameRuleCore();
            for (String page : pages) {
                Board raw = parseBoard(page, 1);
                if (prev==null) {
                    Board numbered = core.renumber(raw, 1);
                    list.add(numbered);
                    prev=numbered;
                    nextId=numbered.maxId()+1;
                } else {
                    var eval=core.evaluate(prev, java.math.BigDecimal.ONE, 1);
                    Board numbered=core.continueIds(prev, raw, eval.winningIds());
                    list.add(numbered);
                    prev=numbered;
                }
            }
            spins.add(List.copyOf(list));
        }
        return new CompleteRoundFact(featureBuy, spins);
    }

    private void appendBoard(StringBuilder out, Board board) {
        for (int c=0;c<6;c++) {
            if (c>0) out.append(':');
            List<Symbol> col=board.columns().get(c);
            for (int i=0;i<col.size();i++) {
                if (i>0) out.append('.');
                Symbol s=col.get(i);
                out.append(propChar(s.prop())).append(s.grid()).append(s.frame());
            }
        }
        out.append('/');
        for (Symbol s:board.horizontal()) out.append(propChar(s.prop()));
    }

    private Board parseBoard(String page, int startId) {
        int slash=page.indexOf('/');
        if (slash<0 || slash==page.length()-1) throw new IllegalArgumentException("board");
        String[] cols=page.substring(0,slash).split(":", -1);
        if (cols.length!=6) throw new IllegalArgumentException("columns");
        int id=startId;
        var columns=new ArrayList<List<Symbol>>();
        for (String col:cols) {
            var list=new ArrayList<Symbol>();
            if (!col.isEmpty()) {
                for (String seg:col.split("\\.", -1)) {
                    if (seg.length()!=3) throw new IllegalArgumentException("segment "+seg);
                    int prop=propVal(seg.charAt(0));
                    int grid=seg.charAt(1)-'0';
                    int frame=seg.charAt(2)-'0';
                    list.add(new Symbol(id++, prop, grid, frame));
                }
            }
            columns.add(list);
        }
        String hs=page.substring(slash+1);
        if (hs.length()!=4) throw new IllegalArgumentException("horizontals");
        var horizontal=new ArrayList<Symbol>();
        for (int i=0;i<4;i++) horizontal.add(new Symbol(id++, propVal(hs.charAt(i)), 1, 0));
        return new Board(columns, horizontal);
    }

    private static char propChar(int prop) {
        if (prop<1 || prop>13) throw new IllegalArgumentException("prop");
        return "123456789ABCD".charAt(prop-1);
    }
    private static int propVal(char c) {
        int i="123456789ABCD".indexOf(c);
        if (i<0) throw new IllegalArgumentException("prop char");
        return i+1;
    }
}

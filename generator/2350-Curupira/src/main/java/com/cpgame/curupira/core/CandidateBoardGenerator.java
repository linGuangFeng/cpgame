package com.cpgame.curupira.core;
import com.cpgame.curupira.random.WeightedSymbolSampler;
import java.util.ArrayList;
import java.util.List;

/** 随机候选只生成符号事实；计奖与类别在事实生成后处理。 */
public final class CandidateBoardGenerator {
    private final WeightedSymbolSampler symbols;

    public CandidateBoardGenerator(WeightedSymbolSampler symbols) {
        this.symbols = symbols;
    }

    public List<Integer> nextBoard() {
        List<Integer> board = new ArrayList<>(GameRules.CELL_COUNT);
        for (int column = 0; column < GameRules.COLUMNS; column++) {
            boolean scatterUsed = false;
            for (int row = 0; row < GameRules.ROWS; row++) {
                List<Integer> allowed = scatterUsed
                        ? GameRules.SYMBOL_ORDER.stream().filter(id -> id != GameRules.SCATTER).toList()
                        : GameRules.SYMBOL_ORDER;
                int symbol = symbols.nextFrom(allowed);
                board.add(symbol);
                scatterUsed |= symbol == GameRules.SCATTER;
            }
        }
        return List.copyOf(board);
    }
}

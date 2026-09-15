package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactBoardCodecTest {
    @Test void fixedMapPreservesEverySymbolHeightAndAdjacentBlockBoundary() {
        String[] symbols = {"Pan", "H1", "H2", "H3", "H4", "H5", "A", "K", "Q", "J", "T", "Wild", "Scat"};
        String codes = "PFGHILAKQJTWS";
        for (int s = 0; s < symbols.length; s++) {
            for (int height = 1; height <= 4; height++) {
                var tokens = new ArrayList<>(Collections.nCopies(34, "1H3"));
                tokens.set(6, height + symbols[s]);
                for (int n = 1; n < height; n++) tokens.remove(7);
                var board = LuckyPandaBoard.fromRskl(tokens);
                String encoded = CompactBoardCodec.encode(board);
                String token = (height == 1 ? "" : Integer.toString(height)) + codes.charAt(s);
                assertEquals("H".repeat(6) + token + "H".repeat(28 - height), encoded);
                assertEquals(tokens, CompactBoardCodec.decode(encoded).toRskl());
            }
        }
        assertEquals(Collections.nCopies(34, "1H3"), CompactBoardCodec.decode("H".repeat(34)).toRskl());
        for (String bad : List.of("", "1H", "0H", "5H", "22H", "2", "h", "?", "H,H"))
            assertThrows(IllegalArgumentException.class, () -> CompactBoardCodec.decode(bad));
    }
}

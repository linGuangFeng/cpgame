package com.cpgame.curupira;

import com.cpgame.curupira.api.DemoCatalog;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DemoCatalogCoverageTest {
    @Test
    void twentyPaidSlotsCoverLossWinExpandingWildAndScatterTrigger() {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        int loss = 0, win = 0, ew = 0, trigger = 0;
        for (int i = 0; i < 20; i++) {
            Kind kind = DemoCatalog.slot(i).kind();
            kinds.add(kind);
            switch (kind) {
                case LOSS -> loss++;
                case WIN -> win++;
                case EXPANDING_WILD -> ew++;
                case TRIGGER -> trigger++;
                default -> throw new AssertionError(kind);
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder(Kind.LOSS, Kind.WIN, Kind.EXPANDING_WILD, Kind.TRIGGER);
        assertThat(trigger).isGreaterThanOrEqualTo(3);
        assertThat(ew).isGreaterThanOrEqualTo(3);
        assertThat(DemoCatalog.slot(1).kind()).isEqualTo(Kind.TRIGGER);
        assertThat(DemoCatalog.slot(2).kind()).isEqualTo(Kind.EXPANDING_WILD);
    }
}

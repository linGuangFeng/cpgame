package com.hd.cpgame.riocarnival.server.state;

import com.hd.cpgame.riocarnival.core.SpinStep;
import java.math.BigDecimal;

public final class DeliveryReceipt {
    public String roundKey;
    public int stepIndex;
    public SpinStep step;
    public BigDecimal playerBalance;
    public DeliveryReceipt() {}
    public DeliveryReceipt(String roundKey, int stepIndex, SpinStep step, BigDecimal playerBalance) {
        this.roundKey=roundKey; this.stepIndex=stepIndex; this.step=step; this.playerBalance=playerBalance;
    }
}

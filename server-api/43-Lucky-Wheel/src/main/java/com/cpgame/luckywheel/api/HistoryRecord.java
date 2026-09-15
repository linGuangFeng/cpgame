package com.cpgame.luckywheel.api;

import com.cpgame.luckywheel.core.SpinResult;
import java.io.Serializable;

public record HistoryRecord(String transferId, String bid, String roundKey, int deliveryIndex, SpinResult result)
        implements Serializable { }

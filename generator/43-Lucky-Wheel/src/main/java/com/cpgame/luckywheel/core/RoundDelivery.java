package com.cpgame.luckywheel.core;

import java.io.Serializable;

public record RoundDelivery(int deliveryIndex, SpinResult result) implements Serializable { }

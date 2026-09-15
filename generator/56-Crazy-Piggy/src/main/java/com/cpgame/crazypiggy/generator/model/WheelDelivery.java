package com.cpgame.crazypiggy.generator.model;

/** 完整 Round 内嵌的轮盘 Delivery；terminal=true 时 multiplier 必须为空。 */
public record WheelDelivery(int deliveryIndex, int position, Integer multiplier, boolean terminal) {}

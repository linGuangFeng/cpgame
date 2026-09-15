package com.cpgame.coinmastergo.model;

import java.util.ArrayList;
import java.util.List;

public class RoundDelivery {
    public String mode;
    public List<SpinStep> steps = new ArrayList<>();

    public RoundDelivery() { }

    public RoundDelivery(String mode, List<SpinStep> steps) {
        this.mode = mode;
        this.steps = steps;
    }
}

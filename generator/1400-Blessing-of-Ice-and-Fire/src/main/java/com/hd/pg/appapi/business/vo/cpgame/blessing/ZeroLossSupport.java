package com.hd.pg.appapi.business.vo.cpgame.blessing;

import java.util.*;
import java.util.function.*;

/** Immutable ten-result reserve; request work is bounded to five fresh proposals. */
final class ZeroLossSupport<T> {
    static final int ATTEMPTS = 5, DEFAULT_COUNT = 10;
    private final List<T> defaults;
    private final Predicate<T> valid;
    private final UnaryOperator<T> copy;
    ZeroLossSupport(Supplier<T> construct, Predicate<T> valid, UnaryOperator<T> copy) {
        this.valid=valid; this.copy=copy;
        List<T> pool=new ArrayList<>(DEFAULT_COUNT);
        for(int i=0;i<DEFAULT_COUNT;i++) {
            T candidate=construct.get();
            if(!valid.test(candidate))throw new IllegalStateException("invalid default zero result");
            pool.add(copy.apply(candidate));
        }
        defaults=Collections.unmodifiableList(pool);
    }
    T generate(Supplier<T> construct, IntUnaryOperator random) {
        for(int attempt=0;attempt<ATTEMPTS;attempt++) {
            T candidate=construct.get();
            if(candidate!=null && valid.test(candidate))return candidate;
        }
        return copy.apply(defaults.get(random.applyAsInt(DEFAULT_COUNT)));
    }
}

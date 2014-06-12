package com.hello.suripu.algorithm.sleepdetection;

/**
 * Created by pangwu on 6/11/14.
 */
class RankFactor {
    public final double errorDiff;
    public final SleepThreshold threshold;

    public RankFactor(final double errorDiff, final SleepThreshold threshold){
        this.errorDiff = errorDiff;
        this.threshold = threshold;
    }
}

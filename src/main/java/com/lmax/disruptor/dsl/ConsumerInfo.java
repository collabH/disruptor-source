package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * 消费者信息接口
 */
interface ConsumerInfo
{
    /**
     * 得到序号数组
     * @return {@link Sequence}
     */
    Sequence[] getSequences();

    /**
     * 得到序号栅栏
     * @return {@link SequenceBarrier}
     */
    SequenceBarrier getBarrier();

    /**
     * 是否结束了调用链
     * @return {@link Boolean}
     */
    boolean isEndOfChain();

    /**
     * 开启消费
     * @param executor {@link Executor}
     */
    void start(Executor executor);

    /**
     * 停止消费
     */
    void halt();

    void markAsUsedInBarrier();

    boolean isRunning();
}

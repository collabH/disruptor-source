/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

/**
 * Sleeping strategy that initially spins, then uses a Thread.yield(), and
 * eventually sleep (<code>LockSupport.parkNanos(n)</code>) for the minimum
 * number of nanos the OS and JVM will allow while the
 * {@link com.lmax.disruptor.EventProcessor}s are waiting on a barrier.
 * 开始旋转，然后使用Thread.yield()和的睡眠策略
 * 最终睡眠(<code>LockSupport.parkNanos(n)</code>)为最小
 * 操作系统和JVM将允许的nano数量
 * {@link com.lmax.disruptor。事件处理器正在等待一个屏障。
 * <p>
 * This strategy is a good compromise between performance and CPU resource.
 * Latency spikes can occur after quiet periods.  It will also reduce the impact
 * on the producing thread as it will not need signal any conditional variables
 * to wake up the event handling thread.
 * 这种策略是性能和CPU资源之间的一种很好的折衷。
 * 潜伏期峰值可以在安静期之后出现。它也将减少影响，因为它不需要任何条件变量的信号唤醒事件处理线程。
 */
public final class SleepingWaitStrategy implements WaitStrategy
{
    /**
     * 默认重试次数
     */
    private static final int DEFAULT_RETRIES = 200;
    /**
     * 默认睡眠时间 100Ns
     */
    private static final long DEFAULT_SLEEP = 100;

    /**
     * 重试次数
     */
    private final int retries;
    /**
     * 睡眠时间Ns
     */
    private final long sleepTimeNs;

    public SleepingWaitStrategy()
    {
        this(DEFAULT_RETRIES, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries)
    {
        this(retries, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries, long sleepTimeNs)
    {
        this.retries = retries;
        this.sleepTimeNs = sleepTimeNs;
    }

    @Override
    public long waitFor(
        final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException
    {
        long availableSequence;
        int counter = retries;

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            counter = applyWaitMethod(barrier, counter);
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    /**
     * 引用等待方法
     * @param barrier 序号栅栏
     * @param counter 计数器
     * @return
     * @throws AlertException
     */
    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
        throws AlertException
    {
        barrier.checkAlert();

        if (counter > 100)
        {
            --counter;
        }
        else if (counter > 0)
        {
            --counter;
            Thread.yield();
        }
        else
        {
            LockSupport.parkNanos(sleepTimeNs);
        }

        return counter;
    }
}

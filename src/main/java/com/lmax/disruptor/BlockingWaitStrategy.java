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

import com.lmax.disruptor.util.ThreadHints;

/**
 * 阻塞策略，它为等待屏障的{@link EventProcessor}使用锁和条件变量。
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 * 当吞吐量和低延迟不如CPU资源重要时，可以使用此策略。
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    //对象锁
    private final Object mutex = new Object();

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        //可用的序号
        long availableSequence;
        //当前最大生产者序号小于下一个消费者序号时，消费者消费速度大于生产者投递速度
        //9<10
        if (cursorSequence.get() < sequence)
        {
            //获取锁
            synchronized (mutex)
            {
                //生产者序号小于消费者序号时，消费者消费速度大于生产者投递速度
                while (cursorSequence.get() < sequence)
                {
                    //检查是否已引发警报，如果已引发，则抛出{@link AlertException}。
                    barrier.checkAlert();
                    //对象锁等待
                    mutex.wait();
                }
            }
        }

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        //缓存等待的消费者，使用jvm的对象锁
        synchronized (mutex)
        {
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
            "mutex=" + mutex +
            '}';
    }
}

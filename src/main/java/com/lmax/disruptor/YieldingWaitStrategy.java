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


/**
 * Yielding strategy that uses a Thread.yield() for {@link com.lmax.disruptor.EventProcessor}s waiting on a barrier
 * after an initially spinning.
 * Yielding strategy使用Thread.yield()为时间处理器等待障碍经过最初的旋转。
 * <p>
 * This strategy will use 100% CPU, but will more readily give up the CPU than a busy spin strategy if other threads
 * require CPU resource.
 * 这个策略将使用100%的CPU，但是如果是其他线程，则会比繁忙的自旋策略更容易放弃CPU需要CPU资源。
 */
public final class YieldingWaitStrategy implements WaitStrategy
{
    //自旋次数
    private static final int SPIN_TRIES = 100;

    @Override
    public long waitFor(
        final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        //自旋计数器
        int counter = SPIN_TRIES;

        //可用序号=当前消费序号，如果消费序号小于可用序号
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            //执行等待方法
            counter = applyWaitMethod(barrier, counter);
        }
        //返回可用序号
        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
        throws AlertException
    {
        //屏障校验
        barrier.checkAlert();
        //如果执行100次后就切换线程不断尝试
        if (0 == counter)
        {
            //线程切换，不断切换
            Thread.yield();
        }
        else
        {
            --counter;
        }

        return counter;
    }
}

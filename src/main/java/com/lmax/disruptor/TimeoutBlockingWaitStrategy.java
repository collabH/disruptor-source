package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;

import static com.lmax.disruptor.util.Util.awaitNanos;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * However it will periodically wake up if it has been idle for specified period by throwing a
 * {@link TimeoutException}.  To make use of this, the event handler class should implement the {@link TimeoutHandler},
 * which the {@link BatchEventProcessor} will call if the timeout occurs.
 * 阻塞策略，它为等待屏障的{@link EventProcessor}使用锁和条件变量。
 * 但是，如果它已经被闲置了指定的时间，它会周期性的唤醒
 *  {@link TimeoutException}。为了利用这一点，事件处理程序类应该实现{@link TimeoutHandler}，
 * 如果超时发生，{@link BatchEventProcessor}将调用哪个。
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 * 当吞吐量和低延迟不如CPU资源重要时，可以使用此策略
 */
public class TimeoutBlockingWaitStrategy implements WaitStrategy
{
    //对象锁
    private final Object mutex = new Object();
    //超时事件
    private final long timeoutInNanos;

    public TimeoutBlockingWaitStrategy(final long timeout, final TimeUnit units)
    {
        timeoutInNanos = units.toNanos(timeout);
    }

    @Override
    public long waitFor(
        final long sequence,
        final Sequence cursorSequence,
        final Sequence dependentSequence,
        final SequenceBarrier barrier)
        throws AlertException, InterruptedException, TimeoutException
    {
        long timeoutNanos = timeoutInNanos;

        long availableSequence;
        //如果游标序号小于等待的序号
        if (cursorSequence.get() < sequence)
        {
            //锁住
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence)
                {
                    barrier.checkAlert();
                    timeoutNanos = awaitNanos(mutex, timeoutNanos);
                    if (timeoutNanos <= 0)
                    {
                        //超时抛出异常
                        throw TimeoutException.INSTANCE;
                    }
                }
            }
        }

        //可用序号=消费者序号
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        synchronized (mutex)
        {
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "TimeoutBlockingWaitStrategy{" +
            "mutex=" + mutex +
            ", timeoutInNanos=" + timeoutInNanos +
            '}';
    }
}

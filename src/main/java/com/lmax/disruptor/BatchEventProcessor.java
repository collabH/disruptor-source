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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * 核心事件循环处理器
 * 单消费者处理同一批事件
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * 处理从{@link RingBuffer}消费条目的批处理语义的便利类并将可用事件委托给{@link EventHandler}。
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *如果{@link EventHandler}也实现了{@link LifecycleAware}，它将在线程结束后被通知在线程关闭之前启动。
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 * 事件实现存储数据，以便在交换或事件的并行协调期间进行共享。
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    //空闲状态 0
    private static final int IDLE = 0;
    //停止状态 1
    private static final int HALTED = IDLE + 1;
    //允许状态 2
    private static final int RUNNING = HALTED + 1;

    //是否运行状态，默认为闲置
    private final AtomicInteger running = new AtomicInteger(IDLE);
    //异常处理器
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    //存储真实数据
    private final DataProvider<T> dataProvider;
    //序号栅栏，维护生产者和消费者进度的协调类
    private final SequenceBarrier sequenceBarrier;
    //消费者接口实现
    private final EventHandler<? super T> eventHandler;
    //ringBuffer真实的有效的最大的序号
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    //超时处理器
    private final TimeoutHandler timeoutHandler;
    //批处理
    private final BatchStartAware batchStartAware;

    /**
     * 构造一个事件处理器，它将通过更新它的序列来自动跟踪进程{@link EventHandler#onEvent(Object, long, boolean)}方法返回
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        //如果消费者接口实现是SequenceReportingEventHandler类，用于设置回调
        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            //设置回调，为了通知BatchEventProcessor该序号已经被处理类
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        //设置停止状态
        running.set(HALTED);
        //告诉事件处理器状态的变化，并保持此状态直到清除。
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        //cas的方式去启动
        if (running.compareAndSet(IDLE, RUNNING))
        {
            //清除当前警报状态。
            sequenceBarrier.clearAlert();

            //通知启动
            notifyStart();
            try
            {
                //如果是执行状态
                if (running.get() == RUNNING)
                {
                    //执行实现集合
                    processEvents();
                }
            }
            finally
            {
                //最后通知关闭
                notifyShutdown();
                //运行状态设置为空闲
                running.set(IDLE);
            }
        }
        else
        {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.

            //这是一个小小的猜测。运行状态可以改为暂停
            //这一点。然而，Java没有compareAndExchange，这是唯一的方法
            //使它完全正确。
            if (running.get() == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                //更早的退出
                earlyExit();
            }
        }
    }

    /**
     * 执行事件
     */
    private void processEvents()
    {
        //用于接受实际的对象
        T event = null;
        //得到下一个序号为当先序号+1，消费者需要
        long nextSequence = sequence.get() + 1L;

        //自旋
        while (true)
        {
            try
            {
                //执行等待策略，得到真实可用序号
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                //如果可用序号大于等于下一个序号或者batchStartAware不为null
                if (batchStartAware != null && availableSequence >= nextSequence)
                {
                    //启动批量
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }
                //生产者的投递速度大于消费速度，真实的最大序号，下一个消费者消费的序号
                while (nextSequence <= availableSequence)
                {
                    //得到具体的消费者
                    event = dataProvider.get(nextSequence);
                    //执行执行
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    //然后一直循环直到大于真实的最大序号
                    nextSequence++;
                }
                //设置当前ringBuffer最大的序号
                sequence.set(availableSequence);
            }
            catch (final TimeoutException e)
            {
                notifyTimeout(sequence.get());
            }
            //配合等待策略中的告警打断线程
            catch (final AlertException ex)
            {
                //如果真的停止允许就break掉
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     * 在处理器启动是通知EventHandler
     */
    private void notifyStart()
    {
        //如果eventHandler类型为LifecycleAware
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                //启动
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                //异常处理器处理启动异常
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
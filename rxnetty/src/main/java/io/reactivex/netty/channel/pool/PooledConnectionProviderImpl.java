/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivex.netty.channel.pool;

import io.reactivex.netty.channel.Connection;
import io.reactivex.netty.channel.client.PoolExhaustedException;
import io.reactivex.netty.channel.client.PoolLimitDeterminationStrategy;
import io.reactivex.netty.events.Clock;
import io.reactivex.netty.events.EventPublisher;
import io.reactivex.netty.events.ListenersHolder;
import io.reactivex.netty.protocol.tcp.client.ClientConnectionToChannelBridge.PooledConnectionReleaseEvent;
import io.reactivex.netty.protocol.tcp.client.ClientState;
import io.reactivex.netty.protocol.tcp.client.ConnectionFactory;
import io.reactivex.netty.protocol.tcp.client.ConnectionObservable;
import io.reactivex.netty.protocol.tcp.client.ConnectionObservable.OnSubcribeFunc;
import io.reactivex.netty.protocol.tcp.client.events.TcpClientEventListener;
import io.reactivex.netty.protocol.tcp.client.events.TcpClientEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Actions;
import rx.functions.Func1;

import java.net.SocketAddress;

import static io.reactivex.netty.protocol.tcp.client.internal.TcpEventPublisherFactory.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * An implementation of {@link PooledConnectionProvider} that pools connections. Configuration of the pool is as defined
 * by {@link PoolConfig} passed in with the {@link ClientState}.
 *
 * Following are the key parameters:
 *
 * <ul>
 <li>{@link PoolLimitDeterminationStrategy}: A stratgey to determine whether a new physical connection should be
 created as part of the user request.</li>
 <li>{@link PoolConfig#getIdleConnectionsCleanupTimer()}: The schedule for cleaning up idle connections in the pool.</li>
 <li>{@link PoolConfig#getMaxIdleTimeMillis()}: Maximum time a connection can be idle in this pool.</li>
 </ul>
 *
 * @param <W> Type of object that is written to the client using this factory.
 * @param <R> Type of object that is read from the the client using this factory.
 */
public final class PooledConnectionProviderImpl<W, R> extends PooledConnectionProvider<W, R> {

    private static final Logger logger = LoggerFactory.getLogger(PooledConnectionProviderImpl.class);

    private final Subscription idleConnCleanupSubscription;
    private final IdleConnectionsHolder<W, R> idleConnectionsHolder;

    private final PoolLimitDeterminationStrategy limitDeterminationStrategy;
    private final long maxIdleTimeMillis;
    private final ConnectionFactory<W, R> connFactory;
    private final SocketAddress host;

    public PooledConnectionProviderImpl(ConnectionFactory<W, R> connFactory, PoolConfig<W, R> poolConfig,
                                        SocketAddress host) {
        super(connFactory);
        this.connFactory = connFactory;
        this.host = host;
        idleConnectionsHolder = poolConfig.getIdleConnectionsHolder();
        limitDeterminationStrategy = poolConfig.getPoolLimitDeterminationStrategy();
        maxIdleTimeMillis = poolConfig.getMaxIdleTimeMillis();
        // In case, there is no cleanup required, this observable should never give a tick.
        idleConnCleanupSubscription = poolConfig.getIdleConnectionsCleanupTimer()
                                                .doOnError(LogErrorAction.INSTANCE)
                .retry() // Retry when there is an error in timer.
                .concatMap(new IdleConnectionCleanupTask())
                .onErrorResumeNext(new Func1<Throwable, Observable<Void>>() {
                    @Override
                    public Observable<Void> call(Throwable throwable) {
                        logger.error("Ignoring error cleaning up idle connections.",
                                     throwable);
                        return Observable.empty();
                    }
                }) // Ignore errors in cleanup.
                .subscribe(Actions.empty()); // Errors are logged and ignored.
    }

    @Override
    public ConnectionObservable<R, W> nextConnection() {
        if (isShutdown()) {
            return ConnectionObservable.forError(new IllegalStateException("Connection provider is already shutdown."));
        }

        return ConnectionObservable.createNew(new OnSubcribeFunc<R, W>() {

            private final ListenersHolder<TcpClientEventListener> listeners = new ListenersHolder<>();

            @Override
            public void call(Subscriber<? super Connection<R, W>> subscriber) {
                idleConnectionsHolder.pollThisEventLoopConnections()
                                     .concatWith(connectIfAllowed(listeners))
                                     .filter(new Func1<PooledConnection<R, W>, Boolean>() {
                                         @Override
                                         public Boolean call(PooledConnection<R, W> c) {
                                             boolean isUsable = c.isUsable();
                                             if (!isUsable) {
                                                 discardNow(c);
                                             }
                                             return isUsable;
                                         }
                                     })
                                     .take(1)
                                     .lift(new ReuseSubscriberLinker())
                                     .lift(new ConnectMetricsOperator(listeners))
                                     .unsafeSubscribe(subscriber);
            }

            @Override
            public Subscription subscribeForEvents(TcpClientEventListener eventListener) {
                return listeners.subscribe(eventListener);
            }
        });
    }

    @Override
    public Observable<Void> release(final PooledConnection<R, W> connection) {
        return Observable.create(new OnSubscribe<Void>() {
            @Override
            public void call(Subscriber<? super Void> subscriber) {
                if (null == connection) {
                    subscriber.onCompleted();
                } else {
                    /**
                     * Executing the release on the eventloop to avoid race-conditions between code cleaning up
                     * connection in the pipeline and the connecting being released to the pool.
                     */
                    connection.unsafeNettyChannel()
                              .eventLoop()
                              .submit(new ReleaseTask(connection, subscriber));
                }
            }
        });
    }

    @Override
    public Observable<Void> discard(final PooledConnection<R, W> connection) {
        return connection.discard().doOnSubscribe(new Action0() {
            @Override
            public void call() {
                EventPublisher eventPublisher = connection.unsafeNettyChannel().attr(EVENT_PUBLISHER).get();
                if (eventPublisher.publishingEnabled()) {
                    TcpClientEventListener eventListener = connection.unsafeNettyChannel()
                                                                     .attr(TCP_CLIENT_EVENT_LISTENER).get();
                    eventListener.onPooledConnectionEviction();
                }
                limitDeterminationStrategy.releasePermit();/*Since, an idle connection took a permit*/
            }
        });
    }

    @Override
    protected Observable<Void> doShutdown() {
        return Observable.create(new OnSubscribe<Void>() {
            @Override
            public void call(Subscriber<? super Void> subscriber) {
                idleConnCleanupSubscription.unsubscribe();
                subscriber.onCompleted();
            }
        });
    }


    private Observable<PooledConnection<R, W>> connectIfAllowed(final ListenersHolder<TcpClientEventListener> ls) {
        return Observable.create(new OnSubscribe<PooledConnection<R, W>>() {
            @Override
            public void call(Subscriber<? super PooledConnection<R, W>> subscriber) {
                final long startTimeMillis = Clock.newStartTimeMillis();
                if (limitDeterminationStrategy.acquireCreationPermit(startTimeMillis, MILLISECONDS)) {
                    ConnectionObservable<R, W> newConnObsv = connFactory.newConnection(host);
                    ls.subscribeAllTo(newConnObsv);
                    newConnObsv.map(
                            new Func1<Connection<R, W>, PooledConnection<R, W>>() {
                                @Override
                                public PooledConnection<R, W> call(Connection<R, W> connection) {
                                    return PooledConnection.create(PooledConnectionProviderImpl.this,
                                                                   maxIdleTimeMillis, connection);
                                }
                            }).doOnError(new Action1<Throwable>() {
                        @Override
                        public void call(Throwable throwable) {
                            limitDeterminationStrategy.releasePermit(); /*Before connect we acquired.*/
                        }
                    }).unsafeSubscribe(subscriber);
                } else {
                    idleConnectionsHolder.poll()
                                         .switchIfEmpty(Observable.<PooledConnection<R, W>>error(
                                                 new PoolExhaustedException("Client connection pool exhausted.")))
                                         .unsafeSubscribe(subscriber);
                }
            }
        });
    }

    private void discardNow(PooledConnection<R, W> toDiscard) {
        discard(toDiscard).subscribe(Actions.empty(), new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                logger.error("Error discarding connection.", throwable);
            }
        });
    }

    private static class LogErrorAction implements Action1<Throwable> {

        public static final LogErrorAction INSTANCE = new LogErrorAction();

        @Override
        public void call(Throwable throwable) {
            logger.error("Error from idle connection cleanup timer. This will be retried.", throwable);
        }
    }

    private class IdleConnectionCleanupTask implements Func1<Long, Observable<Void>> {
        @Override
        public Observable<Void> call(Long aLong) {
            return idleConnectionsHolder.peek()
                                        .map(new Func1<PooledConnection<R, W>, Void>() {
                                            @Override
                                            public Void call(PooledConnection<R, W> connection) {
                                                if (!connection.isUsable()) {
                                                    idleConnectionsHolder.remove(connection);
                                                    discardNow(connection);
                                                }
                                                return null;
                                            }
                                        }).ignoreElements();
        }
    }

    private class ReleaseTask implements Runnable {

        private final PooledConnection<R, W> connection;
        private final Subscriber<? super Void> subscriber;
        private final long releaseStartTime;
        private final EventPublisher eventPublisher;
        private final TcpClientEventListener eventListener;

        private ReleaseTask(PooledConnection<R, W> connection, Subscriber<? super Void> subscriber) {
            this.connection = connection;
            this.subscriber = subscriber;
            releaseStartTime = Clock.newStartTimeMillis();
            eventPublisher = connection.unsafeNettyChannel().attr(EVENT_PUBLISHER).get();
            eventListener = connection.unsafeNettyChannel().attr(TCP_CLIENT_EVENT_LISTENER).get();
        }

        @Override
        public void run() {
            try {
                connection.unsafeNettyChannel().pipeline().fireUserEventTriggered(PooledConnectionReleaseEvent.INSTANCE);
                if (eventPublisher.publishingEnabled()) {
                    eventListener.onPoolReleaseStart();
                }
                if (isShutdown() || !connection.isUsable()) {
                    discardNow(connection);
                } else {
                    idleConnectionsHolder.add(connection);
                }

                if (eventPublisher.publishingEnabled()) {
                    eventListener.onPoolReleaseSuccess(Clock.onEndMillis(releaseStartTime), MILLISECONDS);
                }
                subscriber.onCompleted();
            } catch (Throwable throwable) {
                if (eventPublisher.publishingEnabled()) {
                    eventListener.onPoolReleaseFailed(Clock.onEndMillis(releaseStartTime), MILLISECONDS, throwable);
                }
                subscriber.onError(throwable);
            }
        }
    }

    private class ConnectMetricsOperator implements Operator<Connection<R, W>, PooledConnection<R, W>> {

        private final ListenersHolder<TcpClientEventListener> listeners;
        /*Modified after the connection is obtained to the one configured on the channel.*/
        private TcpClientEventListener eventListener;

        public ConnectMetricsOperator(ListenersHolder<TcpClientEventListener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public Subscriber<? super PooledConnection<R, W>> call(final Subscriber<? super Connection<R, W>> o) {
            final long startTimeMillis = isEventPublishingEnabled() ? Clock.newStartTimeMillis() : -1;

            if (isEventPublishingEnabled()) {
                listeners.invokeListeners(TcpClientEventPublisher.ACQUIRE_START_ACTION);
            }

            return new Subscriber<PooledConnection<R, W>>(o) {
                @Override
                public void onCompleted() {
                    if (isEventPublishingEnabled()) {
                        if (null != eventListener) {
                            eventListener.onPoolAcquireSuccess(Clock.onEndMillis(startTimeMillis), MILLISECONDS);
                        } else {
                            listeners.invokeListeners(TcpClientEventPublisher.ACQUIRE_SUCCESS_ACTION,
                                                      Clock.onEndMillis(startTimeMillis), MILLISECONDS);
                        }
                    }
                    o.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    if (isEventPublishingEnabled()) {
                        /*Error means no connection was received, as it always every gets at most one connection*/
                        listeners.invokeListeners(TcpClientEventPublisher.ACQUIRE_FAILED_ACTION,
                                                  Clock.onEndMillis(startTimeMillis), MILLISECONDS, e);
                    }
                    o.onError(e);
                }

                @Override
                public void onNext(PooledConnection<R, W> c) {
                    EventPublisher eventPublisher = c.unsafeNettyChannel().attr(EVENT_PUBLISHER).get();
                    if (eventPublisher.publishingEnabled()) {
                        /*Now use the actual listener, the original listener is connected to this one so it will get
                        * the remaining callbacks transitively.*/
                        eventListener = c.unsafeNettyChannel().attr(TCP_CLIENT_EVENT_LISTENER).get();
                    }
                    o.onNext(c);
                }
            };
        }
    }

    private class ReuseSubscriberLinker implements Operator<PooledConnection<R, W>, PooledConnection<R, W>> {

        private ScalarAsyncSubscriber<R, W> onReuseSubscriber;

        @Override
        public Subscriber<? super PooledConnection<R, W>> call(final Subscriber<? super PooledConnection<R, W>> o) {
            return new Subscriber<PooledConnection<R, W>>(o) {

                @Override
                public void onCompleted() {
                    /*This subscriber is not invoked by different threads, so don't need sychronization*/
                    if (null != onReuseSubscriber) {
                        onReuseSubscriber.onCompleted();
                    } else {
                        o.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable e) {
                    /*This subscriber is not invoked by different threads, so don't need sychronization*/
                    if (null != onReuseSubscriber) {
                        onReuseSubscriber.onError(e);
                    } else {
                        o.onError(e);
                    }
                }

                @Override
                public void onNext(PooledConnection<R, W> c) {
                    if (c.isReused()) {
                        EventPublisher eventPublisher = c.unsafeNettyChannel().attr(EVENT_PUBLISHER).get();
                        if (eventPublisher.publishingEnabled()) {
                            TcpClientEventListener eventListener = c.unsafeNettyChannel()
                                                                    .attr(TCP_CLIENT_EVENT_LISTENER).get();
                            eventListener.onPooledConnectionReuse();
                        }
                        onReuseSubscriber = new ScalarAsyncSubscriber<>(o);
                        c.reuse(onReuseSubscriber); /*Reuse will on next to the subscriber*/
                    } else {
                        o.onNext(c);
                    }
                }
            };
        }

    }

    private static class ScalarAsyncSubscriber<R, W> extends Subscriber<PooledConnection<R, W>> {

        private boolean terminated; /*Guarded by this*/
        private Throwable error; /*Guarded by this*/
        private boolean onNextArrived; /*Guarded by this*/
        private final Subscriber<? super  PooledConnection<R, W>> delegate;

        private ScalarAsyncSubscriber(Subscriber<? super PooledConnection<R, W>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onCompleted() {
            boolean _onNextArrived;

            synchronized (this) {
                _onNextArrived = onNextArrived;
            }

            terminated = true;

            if (_onNextArrived) {
                delegate.onCompleted();
            }
        }

        @Override
        public void onError(Throwable e) {
            boolean _onNextArrived;

            synchronized (this) {
                _onNextArrived = onNextArrived;
            }
            terminated = true;
            error = e;

            if (_onNextArrived) {
                delegate.onError(e);
            }
        }

        @Override
        public void onNext(PooledConnection<R, W> conn) {
            boolean _terminated;
            Throwable _error;
            synchronized (this) {
                onNextArrived = true;
                _terminated = terminated;
                _error = error;
            }

            delegate.onNext(conn);

            if (_terminated) {
                if (null != error) {
                    delegate.onError(_error);
                } else {
                    delegate.onCompleted();
                }
            }
        }
    }
}

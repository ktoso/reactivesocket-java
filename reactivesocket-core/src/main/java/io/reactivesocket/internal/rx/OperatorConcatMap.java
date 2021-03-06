/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */
package io.reactivesocket.internal.rx;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.reactivestreams.*;

public final class OperatorConcatMap<T, U> {
    final Function<? super T, ? extends Publisher<? extends U>> mapper;
    final int bufferSize;
    public OperatorConcatMap(Function<? super T, ? extends Publisher<? extends U>> mapper, int bufferSize) {
        this.mapper = mapper;
        this.bufferSize = bufferSize;
    }

    public Subscriber<? super T> apply(Subscriber<? super U> s) {
        SerializedSubscriber<U> ssub = new SerializedSubscriber<>(s);
        SubscriptionArbiter sa = new SubscriptionArbiter();
        ssub.onSubscribe(sa);
        return new SourceSubscriber<>(ssub, sa, mapper, bufferSize);
    }
    
    static final class SourceSubscriber<T, U> extends AtomicInteger implements Subscriber<T> {
        /** */
        private static final long serialVersionUID = 8828587559905699186L;
        final Subscriber<? super U> actual;
        final SubscriptionArbiter sa;
        final Function<? super T, ? extends Publisher<? extends U>> mapper;
        final Subscriber<U> inner;
        final Queue<T> queue;
        final int bufferSize;
        
        Subscription s;
        
        volatile boolean done;
        
        volatile long index;
        
        public SourceSubscriber(Subscriber<? super U> actual, SubscriptionArbiter sa,
                Function<? super T, ? extends Publisher<? extends U>> mapper, int bufferSize) {
            this.actual = actual;
            this.sa = sa;
            this.mapper = mapper;
            this.bufferSize = bufferSize;
            this.inner = new InnerSubscriber<>(actual, sa, this);
            Queue<T> q;
            if (Pow2.isPowerOfTwo(bufferSize)) {
                q = new SpscArrayQueue<>(bufferSize);
            } else {
                q = new SpscExactArrayQueue<>(bufferSize);
            }
            this.queue = q;
        }
        @Override
        public void onSubscribe(Subscription s) {
            if (this.s != null) {
                s.cancel();
                return;
            }
            this.s = s;
            s.request(bufferSize);
        }
        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }
            if (!queue.offer(t)) {
                cancel();
                actual.onError(new IllegalStateException("More values received than requested!"));
                return;
            }
            if (getAndIncrement() == 0) {
                drain();
            }
        }
        @Override
        public void onError(Throwable t) {
            if (done) {
                return;
            }
            done = true;
            cancel();
            actual.onError(t);
        }
        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            if (getAndIncrement() == 0) {
                drain();
            }
        }
        
        void innerComplete() {
            if (decrementAndGet() != 0) {
                drain();
            }
            if (!done) {
                s.request(1);
            }
        }
        
        void cancel() {
            sa.cancel();
            s.cancel();
        }
        
        void drain() {
            boolean d = done;
            T o = queue.poll();
            
            if (o == null) {
                if (d) {
                    actual.onComplete();
                    return;
                }
                return;
            }
            Publisher<? extends U> p;
            try {
                p = mapper.apply(o);
            } catch (Throwable e) {
                cancel();
                actual.onError(e);
                return;
            }
            index++;
            // this is not RS but since our Subscriber doesn't hold state by itself,
            // subscribing it to each source is safe and saves allocation
            p.subscribe(inner);
        }
    }
    
    static final class InnerSubscriber<U> implements Subscriber<U> {
        final Subscriber<? super U> actual;
        final SubscriptionArbiter sa;
        final SourceSubscriber<?, ?> parent;

        /*
         * FIXME this is a workaround for now, but doesn't work 
         * for async non-conforming sources.
         * Such sources require individual instances of InnerSubscriber and a
         * done field.
         */
         
        long index;
        
        public InnerSubscriber(Subscriber<? super U> actual, 
                SubscriptionArbiter sa, SourceSubscriber<?, ?> parent) {
            this.actual = actual;
            this.sa = sa;
            this.parent = parent;
            this.index = 1;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (index == parent.index) {
                sa.setSubscription(s);
            }
        }
        
        @Override
        public void onNext(U t) {
            if (index == parent.index) {
                actual.onNext(t);
                sa.produced(1L);
            }
        }
        @Override
        public void onError(Throwable t) {
            if (index == parent.index) {
                index++;
                parent.cancel();
                actual.onError(t);
            }
        }
        @Override
        public void onComplete() {
            if (index == parent.index) {
                index++;
                parent.innerComplete();
            }
        }
    }
}
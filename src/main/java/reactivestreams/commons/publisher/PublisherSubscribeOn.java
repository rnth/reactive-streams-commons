package reactivestreams.commons.publisher;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.util.DeferredSubscription;
import reactivestreams.commons.util.EmptySubscription;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Subscribes to the source Publisher asynchronously through a scheduler function or
 * ExecutorService.
 * 
 * @param <T> the value type
 */
public final class PublisherSubscribeOn<T> extends PublisherSource<T, T> {

    static final Runnable CANCELLED = new Runnable() {
        @Override
        public void run() {

        }
    };

    final Function<Runnable, Runnable> scheduler;
    
    final boolean eagerCancel;
    
    final boolean requestOn;
    
    public PublisherSubscribeOn(
            Publisher<? extends T> source, 
            Function<Runnable, Runnable> scheduler,
            boolean eagerCancel, 
            boolean requestOn) {
        super(source);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eagerCancel = eagerCancel;
        this.requestOn = requestOn;
    }

    public PublisherSubscribeOn(
            Publisher<? extends T> source, 
            ExecutorService executor,
            boolean eagerCancel, 
            boolean requestOn) {
        super(source);
        Objects.requireNonNull(executor, "executor");
        this.scheduler = new PublisherObserveOn.ExecutorServiceWorker(executor);
        this.eagerCancel = eagerCancel;
        this.requestOn = requestOn;
    }

    static <T> boolean trySupplierScheduleOn(
            Publisher<? extends T> p, 
            Subscriber<? super T> s, 
            Function<Runnable, Runnable> scheduler,
            boolean eagerCancel) {
        if (p instanceof Supplier) {
            
            @SuppressWarnings("unchecked")
            Supplier<T> supplier = (Supplier<T>) p;
            
            T v = supplier.get();
            
            supplierScheduleOnSubscribe(v, s, scheduler, eagerCancel);
            
            return true;
        }
        return false;
    }
    
    static <T> void supplierScheduleOnSubscribe(T v, final Subscriber<? super T> s, Function<Runnable, Runnable>
            scheduler, boolean eagerCancel) {
        if (v == null) {
            if (eagerCancel) {
                ScheduledEmptySubscriptionEager parent = new ScheduledEmptySubscriptionEager(s);
                s.onSubscribe(parent);
                Runnable f = scheduler.apply(parent);
                parent.setFuture(f);
            } else {
                scheduler.apply(new Runnable() {
                    @Override
                    public void run() {
                        EmptySubscription.complete(s);
                    }
                });
            }
        } else {
            if (eagerCancel) {
                s.onSubscribe(new ScheduledSubscriptionEagerCancel<>(s, v, scheduler));
            } else {
                s.onSubscribe(new ScheduledSubscriptionNonEagerCancel<>(s, v, scheduler));
            }
        }
    }
    
    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (trySupplierScheduleOn(source, s, scheduler, eagerCancel)) {
            return;
        }
        if (eagerCancel) {
            if (requestOn) {
                PublisherSubscribeOnClassic<T> parent = new PublisherSubscribeOnClassic<>(s, scheduler);
                s.onSubscribe(parent);
                
                Runnable f = scheduler.apply(new SourceSubscribeTask<>(parent, source));
                parent.setFuture(f);
            } else {
                PublisherSubscribeOnEagerDirect<T> parent = new PublisherSubscribeOnEagerDirect<>(s);
                s.onSubscribe(parent);
                
                Runnable f = scheduler.apply(new SourceSubscribeTask<>(parent, source));
                parent.setFuture(f);
            }
        } else {
            if (requestOn) {
                scheduler.apply(new SourceSubscribeTask<>(new PublisherSubscribeOnNonEager<>(s, scheduler), source));
            } else {
                scheduler.apply(new SourceSubscribeTask<>(s, source));
            }
        }
    }
    
    static final class PublisherSubscribeOnNonEager<T> implements Subscriber<T>, Subscription {
        final Subscriber<? super T> actual;

        final Function<Runnable, Runnable> scheduler;
        
        Subscription s;
        
        public PublisherSubscribeOnNonEager(Subscriber<? super T> actual,
                Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.scheduler = scheduler;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                
                this.s = s;
                
                actual.onSubscribe(this);
            }
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void request(final long n) {
            scheduler.apply(new RequestTask(s, n));
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }

        static final class RequestTask implements Runnable {

            final long n;
            final Subscription s;

            RequestTask(Subscription s, long n) {
                this.n = n;
                this.s = s;
            }

            @Override
            public void run() {
                s.request(n);
            }
        }
    }
    
    static final class PublisherSubscribeOnEagerDirect<T> 
    extends DeferredSubscription
    implements Subscriber<T> {
        final Subscriber<? super T> actual;

        volatile Runnable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSubscribeOnEagerDirect, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(PublisherSubscribeOnEagerDirect.class, Runnable.class, "future");
        
        public PublisherSubscribeOnEagerDirect(Subscriber<? super T> actual) {
            this.actual = actual;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            set(s);
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void cancel() {
            super.cancel();
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
        }
        
        void setFuture(Runnable run) {
            if (!FUTURE.compareAndSet(this, null, run)) {
                run.run();
            }
        }
    }
    
    static final class PublisherSubscribeOnClassic<T>
    extends DeferredSubscription implements Subscriber<T> {
        final Subscriber<? super T> actual;
        
        final Function<Runnable, Runnable> scheduler;

        static final Runnable FINISHED = new Runnable() {
            @Override
            public void run() {

            }
        };

        Collection<Runnable> tasks;
        
        volatile boolean disposed;

        volatile Runnable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSubscribeOnClassic, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(PublisherSubscribeOnClassic.class, Runnable.class, "future");

        public PublisherSubscribeOnClassic(Subscriber<? super T> actual, Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.scheduler = scheduler;
            this.tasks = new LinkedList<>();
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            set(s);
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                ScheduledRequest sr = new ScheduledRequest(n, this);
                add(sr);
                
                Runnable f = scheduler.apply(new DeferredRequestTask(sr));
                
                sr.setFuture(f);
            }
        }
        
        @Override
        public void cancel() {
            super.cancel();
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
            dispose();
        }
        
        void setFuture(Runnable run) {
            if (!FUTURE.compareAndSet(this, null, run)) {
                run.run();
            }
        }

        boolean add(Runnable run) {
            if (!disposed) {
                synchronized (this) {
                    if (!disposed) {
                        tasks.add(run);
                        return true;
                    }
                }
            }
            run.run();
            return false;
        }

        void delete(Runnable run) {
            if (!disposed) {
                synchronized (this) {
                    if (!disposed) {
                        tasks.remove(run);
                    }
                }
            }
        }
        
        void dispose() {
            if (disposed) {
                return;
            }
            
            Collection<Runnable> list;
            synchronized (this) {
                if (disposed) {
                    return;
                }
                disposed = true;
                list = tasks;
                tasks = null;
            }
            
            for (Runnable r : list) {
                r.run();
            }
        }
        
        void requestInner(long n) {
            super.request(n);
        }

        static final class DeferredRequestTask implements Runnable {

            final ScheduledRequest sr;

            public DeferredRequestTask(ScheduledRequest sr) {
                this.sr = sr;
            }

            @Override
            public void run() {
                sr.request();
            }
        }

        static final class ScheduledRequest
        extends AtomicReference<Runnable>
        implements Runnable {
            /** */
            private static final long serialVersionUID = 2284024836904862408L;
            
            final long n;
            final PublisherSubscribeOnClassic parent;

            public ScheduledRequest(long n, PublisherSubscribeOnClassic parent) {
                this.n = n;
                this.parent = parent;
            }
            
            @Override
            public void run() {
                for (;;) {
                    Runnable a = get();
                    if (a == FINISHED) {
                        return;
                    }
                    if (compareAndSet(a, CANCELLED)) {
                        parent.delete(this);
                        return;
                    }
                }
            }
            
            void request() {
                parent.requestInner(n);

                for (;;) {
                    Runnable a = get();
                    if (a == CANCELLED) {
                        return;
                    }
                    if (compareAndSet(a, FINISHED)) {
                        parent.delete(this);
                        return;
                    }
                }
            }
            
            void setFuture(Runnable f) {
                for (;;) {
                    Runnable a = get();
                    if (a == FINISHED) {
                        return;
                    }
                    if (a == CANCELLED) {
                        f.run();
                        return;
                    }
                    if (compareAndSet(null, f)) {
                        return;
                    }
                }
            }
        }
    }
    
    static final class ScheduledSubscriptionEagerCancel<T> implements Subscription, Runnable {

        final Subscriber<? super T> actual;
        
        final T value;
        
        final Function<Runnable, Runnable> scheduler;

        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ScheduledSubscriptionEagerCancel> ONCE =
                AtomicIntegerFieldUpdater.newUpdater(ScheduledSubscriptionEagerCancel.class, "once");

        volatile Runnable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ScheduledSubscriptionEagerCancel, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(ScheduledSubscriptionEagerCancel.class, Runnable.class, "future");

        public ScheduledSubscriptionEagerCancel(Subscriber<? super T> actual, T value, Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.value = value;
            this.scheduler = scheduler;
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (ONCE.compareAndSet(this, 0, 1)) {
                    Runnable f = scheduler.apply(this);
                    if (!FUTURE.compareAndSet(this, null, f)) {
                        f.run();
                    }
                }
            }
        }
        
        @Override
        public void cancel() {
            ONCE.lazySet(this, 1);
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
        }
        
        @Override
        public void run() {
            actual.onNext(value);
            actual.onComplete();
        }
    }

    static final class ScheduledSubscriptionNonEagerCancel<T> implements Subscription, Runnable {

        final Subscriber<? super T> actual;
        
        final T value;
        
        final Function<Runnable, Runnable> scheduler;

        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ScheduledSubscriptionNonEagerCancel> ONCE =
                AtomicIntegerFieldUpdater.newUpdater(ScheduledSubscriptionNonEagerCancel.class, "once");

        public ScheduledSubscriptionNonEagerCancel(Subscriber<? super T> actual, T value, Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.value = value;
            this.scheduler = scheduler;
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (ONCE.compareAndSet(this, 0, 1)) {
                    scheduler.apply(this);
                }
            }
        }
        
        @Override
        public void cancel() {
            once = 1;
        }
        
        @Override
        public void run() {
            actual.onNext(value);
            actual.onComplete();
        }
    }

    static final class PublisherSubscribeOnValue<T> extends PublisherBase<T> {
        final T value;
        
        final Function<Runnable, Runnable> scheduler;

        final boolean eagerCancel;

        public PublisherSubscribeOnValue(T value, Function<Runnable, Runnable> scheduler, boolean eagerCancel) {
            this.value = value;
            this.scheduler = scheduler;
            this.eagerCancel = eagerCancel;
        }

        public PublisherSubscribeOnValue(T value, ExecutorService executor, boolean eagerCancel) {
            this.value = value;
            this.scheduler = new PublisherObserveOn.ExecutorServiceWorker(executor);
            this.eagerCancel = eagerCancel;
        }
        
        @Override
        public void subscribe(Subscriber<? super T> s) {
            supplierScheduleOnSubscribe(value, s, scheduler, eagerCancel);
        }
    }

    static final class ScheduledEmptySubscriptionEager implements Subscription, Runnable {
        final Subscriber<?> actual;
        
        volatile Runnable future;
        static final AtomicReferenceFieldUpdater<ScheduledEmptySubscriptionEager, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(ScheduledEmptySubscriptionEager.class, Runnable.class, "future");

        public ScheduledEmptySubscriptionEager(Subscriber<?> actual) {
            this.actual = actual;
        }
        
        @Override
        public void request(long n) {
            SubscriptionHelper.validate(n);
        }
        
        @Override
        public void cancel() {
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
        }
        
        @Override
        public void run() {
            actual.onComplete();
        }
        
        void setFuture(Runnable f) {
            if (!FUTURE.compareAndSet(this, null, f)) {
                f.run();
            }
        }
    }

    static final class SourceSubscribeTask<T> implements Runnable {

        final Subscriber<? super T> actual;
        final Publisher<? extends T> source;

        public SourceSubscribeTask(Subscriber<? super T> s, Publisher<? extends T> source) {
            this.actual = s;
            this.source = source;
        }

        @Override
        public void run() {
            source.subscribe(actual);
        }
    }
}
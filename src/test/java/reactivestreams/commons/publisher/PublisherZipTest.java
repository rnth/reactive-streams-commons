package reactivestreams.commons.publisher;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.*;

import org.junit.Test;
import org.reactivestreams.Publisher;

import reactivestreams.commons.test.TestSubscriber;
import reactivestreams.commons.util.ConstructorTestBuilder;

public class PublisherZipTest {

    @Test
    public void constructors() {
        ConstructorTestBuilder ctb = new ConstructorTestBuilder(PublisherZip.class);
        
        ctb.addRef("sources", new Publisher[0]);
        ctb.addRef("sourcesIterable", Collections.emptyList());
        ctb.addRef("queueSupplier", (Supplier<Queue<Object>>)() -> new ConcurrentLinkedQueue<>());
        ctb.addInt("prefetch", 1, Integer.MAX_VALUE);
        ctb.addRef("zipper", (Function<Object[], Object>)v -> v);
        
        ctb.test();
    }
    
    @Test
    public void sameLength() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source = PublisherBase.fromIterable(Arrays.asList(1, 2));
        source.zipWith(source, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }

    @Test
    public void sameLengthOptimized() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source = PublisherBase.range(1, 2);
        source.zipWith(source, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }

    @Test
    public void sameLengthBackpressured() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>(0);
        
        PublisherBase<Integer> source = PublisherBase.fromIterable(Arrays.asList(1, 2));
        source.zipWith(source, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertNotComplete();
        
        ts.request(1);

        ts.assertValue(2)
        .assertNoError()
        .assertNotComplete();

        ts.request(2);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }

    @Test
    public void sameLengthOptimizedBackpressured() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>(0);
        
        PublisherBase<Integer> source = PublisherBase.range(1, 2);
        source.zipWith(source, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertNotComplete();
        
        ts.request(1);

        ts.assertValue(2)
        .assertNoError()
        .assertNotComplete();

        ts.request(2);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }

    @Test
    public void differentLength() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.fromIterable(Arrays.asList(1, 2));
        PublisherBase<Integer> source2 = PublisherBase.fromIterable(Arrays.asList(1, 2, 3));
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void differentLengthOpt() {
        
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.range(1, 2);
        PublisherBase<Integer> source2 = PublisherBase.range(1, 3);
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2, 4)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void emptyNonEmpty() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.fromIterable(Collections.emptyList());
        PublisherBase<Integer> source2 = PublisherBase.fromIterable(Arrays.asList(1, 2, 3));
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void nonEmptyAndEmpty() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.fromIterable(Arrays.asList(1, 2, 3));
        PublisherBase<Integer> source2 = PublisherBase.fromIterable(Collections.emptyList());
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void scalarNonScalar() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.just(1);
        PublisherBase<Integer> source2 = PublisherBase.fromIterable(Arrays.asList(1, 2, 3));
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void scalarNonScalarBackpressured() {
        TestSubscriber<Integer> ts = new TestSubscriber<>(0);
        
        PublisherBase<Integer> source1 = PublisherBase.just(1);
        PublisherBase<Integer> source2 = PublisherBase.fromIterable(Arrays.asList(1, 2, 3));
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertNotComplete();
        
        ts.request(1);
        
        ts.assertValues(2)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void scalarNonScalarOpt() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.just(1);
        PublisherBase<Integer> source2 = PublisherBase.range(1, 3);
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void scalarScalar() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.just(1);
        PublisherBase<Integer> source2 = PublisherBase.just(1);
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertValues(2)
        .assertNoError()
        .assertComplete();
    }
    
    @Test
    public void emptyScalar() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        PublisherBase<Integer> source1 = PublisherBase.empty();
        PublisherBase<Integer> source2 = PublisherBase.just(1);
        source1.zipWith(source2, (a, b) -> a + b).subscribe(ts);
        
        ts.assertNoValues()
        .assertNoError()
        .assertComplete();
    }
}
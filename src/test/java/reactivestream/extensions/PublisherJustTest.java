package reactivestream.extensions;

import org.junit.*;

import reactivestream.extensions.internal.subscribers.TestSubscriber;

public class PublisherJustTest {

    @Test(expected = NullPointerException.class)
    public void nullValue() {
        new PublisherJust<Integer>(null);
    }
    
    @Test
    public void valueSame() {
        Assert.assertSame(1, new PublisherJust<>(1).get());
    }
    
    @Test
    public void normal() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();
        
        new PublisherJust<>(1).subscribe(ts);
        
        ts.assertValue(1)
        .assertComplete()
        .assertNoError();
    }

    @Test
    public void normalBackpressured() {
        TestSubscriber<Integer> ts = new TestSubscriber<>(0);
        
        new PublisherJust<>(1).subscribe(ts);
        
        ts.assertNoValues()
        .assertNotComplete()
        .assertNoError();
        
        ts.request(1);
        
        ts.assertValue(1)
        .assertComplete()
        .assertNoError();
    }
    
}

package  com.cyborg;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;


public class Task implements Runnable {

    // Each task maintains its list of records which it gets during handleTask from main thread of poll.
    private final List<ConsumerRecord<String, String>> records;

    private volatile boolean stopped = false;

    private volatile boolean started = false;

    private volatile boolean finished = false;

    private final Logger log = LoggerFactory.getLogger(com.cyborg.Task.class);

    // Completable is very useful, to track completion of task.
    // When task is complete at end of run(), set completion.complete(offset.get())
    // we can also check if the task is complete by making a completion.get(),blocking call.
    // difference with future is that, in completablefuture we can mark a future as complete.
    private final CompletableFuture<Long> completion = new CompletableFuture<>();

    private final ReentrantLock startStopLock = new ReentrantLock();

    // Atomic counter that maintains the current offset processed.
    private final AtomicLong currentOffset = new AtomicLong();


    public Task(List<ConsumerRecord<String, String>> records) {
        this.records = records;
    }


    public void run() {
        startStopLock.lock();
        if (stopped){
            return;
        }
        started = true;
        startStopLock.unlock();

        for (ConsumerRecord<String, String> record : records) {
            if (stopped)
                break;
            // process record here and make sure you catch all exceptions;

                System.out.printf(Thread.currentThread().getName() + "offset = %d, key = %s, value = %s\n",
                        record.offset(), record.key(), record.value());
            log.info("Thread {0} consuming {1} {2} {3} ", Thread.currentThread().getName() , record.offset(), record.key(), record.value());
            currentOffset.set(record.offset() + 1);
        }
        finished = true;
        completion.complete(currentOffset.get());
    }

    public long getCurrentOffset() {
        return currentOffset.get();
    }

    public void stop() {
        startStopLock.lock();
        this.stopped = true;
        if (!started) {
            finished = true;
            completion.complete(currentOffset.get());
        }
        startStopLock.unlock();
    }

    public long waitForCompletion() {
        try {
            return completion.get();
        } catch (InterruptedException | ExecutionException e) {
            return -1;
        }
    }

    public boolean isFinished() {
        return finished;
    }

}

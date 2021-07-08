
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class Consumer implements Runnable, ConsumerRebalanceListener {

    private final KafkaConsumer<String, String> consumer;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Map<TopicPartition, Task> activeTasks = new HashMap<>();
    private final Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private long lastCommitTime = System.currentTimeMillis();
    private final Logger log = LoggerFactory.getLogger(Consumer.class);


    public Consumer(String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:29092");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "multithreaded-consumer-demo");
        consumer = new KafkaConsumer<>(config);
        new Thread(this).start();
    }


    @Override
    public void run() {
        try {
           // synchronized (this) {
            consumer.subscribe(Collections.singleton("high-scores"), this);
            while (!stopped.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.of(100, ChronoUnit.MILLIS));
                handleFetchedRecords(records);
                checkActiveTasks();
                commitOffsets();
          //  }
            }
        } catch (WakeupException we) {
            if (!stopped.get())
                throw we;
        } finally {
            consumer.close();
        }
    }

    // As the consumer runs, three things happen
    // 1. Handle the records.The consumer poll, returns records at every poll interval.
    // We can specify max.poll.interval in seconds, max.poll.size too.
    // Pass those records to the handleFetchRecords method which identifies partitions, iterates
    // each partition creates those many tasks as partitions are unit of tasks from where seqeuntial /
    // processing can be done by each thread.
    // also maintains map of activetasks by key as partition , value as the thread task

    // Since main thread is not waiting for a task in a partition is completed by a thread,
    // in order to ensure the processing guarantees of a partition in order,
    // once we spin a thread per partition we pause the consumer on that partition so
    // that in the next poll it does not fetch data from that prtition
    // and resume that partition when the task has been completed on that partition
    private void handleFetchedRecords(ConsumerRecords<String, String> records) {
        if (records.count() > 0) {
           /* for (ConsumerRecord<String, String> record : records)
                System.out.printf("offset = %d, key = %s, value = %s\n",
                        record.offset(), record.key(), record.value()); */

            List<TopicPartition> partitionsToPause = new ArrayList<>();
            records.partitions().forEach(partition -> {
                List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
                Task task = new Task(partitionRecords);
                partitionsToPause.add(partition);
                executor.submit(task);
                activeTasks.put(partition, task);
            });
            consumer.pause(partitionsToPause);
        }
    }

    // 3. Check if time elapsed(current time elapsed - lastcommit)  is > 5 seconds, which is default poll interval,
    // then read the offSetToCommit map and sync commit the offset.
    // Map<TopicPartition, OffsetAndMetadata> offsetsToCommit
    // clear the map and log last commit time
    private void commitOffsets() {
        try {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastCommitTime > 5000) {
                if(!offsetsToCommit.isEmpty()) {
                    consumer.commitSync(offsetsToCommit);
                    offsetsToCommit.clear();
                }
                lastCommitTime = currentTimeMillis;
            }
        } catch (Exception e) {
            log.error("Failed to commit offsets!", e);
        }
    }

    //https://www.confluent.io/blog/kafka-consumer-multi-threaded-messaging/
   // 2. This is the second task.
    // Check the activetasks map which is having partition and task,
    // ask and check if each task has completed, if yes remove from activeTask map and get the offset and
    // add it to the offsetcommit map.
    private void checkActiveTasks() {
        List<TopicPartition> finishedTasksPartitions = new ArrayList<>();
        activeTasks.forEach((partition, task) -> {
            if (task.isFinished())
                finishedTasksPartitions.add(partition);
            long offset = task.getCurrentOffset();
            if (offset > 0)
                offsetsToCommit.put(partition, new OffsetAndMetadata(offset));
        });
        finishedTasksPartitions.forEach(partition -> activeTasks.remove(partition));
        consumer.resume(finishedTasksPartitions);
    }

   // Identify the partitions which are subjected to revoke
    // iterate each partition and wait for the task to complete,
    // get the last offset and commit it.
    // also commit offsets for revoked partitions
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {

        // 1. Stop all tasks handling records from revoked partitions
        Map<TopicPartition, Task> stoppedTask = new HashMap<>();
        for (TopicPartition partition : partitions) {
            Task task = activeTasks.remove(partition);
            if (task != null) {
                task.stop();
                stoppedTask.put(partition, task);
            }
        }

        // 2. Wait for stopped tasks to complete processing of current record
        stoppedTask.forEach((partition, task) -> {
            long offset = task.waitForCompletion();
            if (offset > 0)
                offsetsToCommit.put(partition, new OffsetAndMetadata(offset));
        });


        // 3. collect offsets for revoked partitions
        Map<TopicPartition, OffsetAndMetadata> revokedPartitionOffsets = new HashMap<>();
        partitions.forEach( partition -> {
            OffsetAndMetadata offset = offsetsToCommit.remove(partition);
            if (offset != null)
                revokedPartitionOffsets.put(partition, offset);
        });

        // 4. commit offsets for revoked partitions
        try {
            consumer.commitSync(revokedPartitionOffsets);
        } catch (Exception e) {
            log.warn("Failed to commit offsets for revoked partitions!");
        }
    }

    // consume resume the partitions
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        consumer.resume(partitions);
    }


    public void stopConsuming() {
        stopped.set(true);
        consumer.wakeup();
    }

}

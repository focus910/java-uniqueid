package org.lable.util.uniqueid.zookeeper;

import org.apache.zookeeper.ZooKeeper;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.lable.util.uniqueid.BaseUniqueIDGenerator;
import org.lable.util.uniqueid.GeneratorException;
import org.lable.util.uniqueid.IDGenerator;
import org.lable.util.uniqueid.zookeeper.connection.ZooKeeperConnection;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.lable.util.uniqueid.zookeeper.ResourceTestPoolHelper.prepareClusterID;
import static org.lable.util.uniqueid.zookeeper.ResourceTestPoolHelper.prepareEmptyQueueAndPool;

public class SynchronizedUniqueIDGeneratorIT {

    String zookeeperQuorum;
    String znode = "/unique-id-generator";

    @Rule
    public ZooKeeperInstance zkInstance = new ZooKeeperInstance();

    final static int CLUSTER_ID = 4;

    @Before
    public void before() throws Exception {
        zookeeperQuorum = zkInstance.getQuorumAddresses();
        ZooKeeperConnection.configure(zookeeperQuorum);
        ZooKeeperConnection.reset();
        ZooKeeper zookeeper = ZooKeeperConnection.get();
        prepareEmptyQueueAndPool(zookeeper, znode);
        prepareClusterID(zookeeper, znode, CLUSTER_ID);
    }

    @Test
    public void simpleTest() throws Exception {
        IDGenerator generator = SynchronizedUniqueIDGenerator.generator(zookeeperQuorum, znode);
        byte[] result = generator.generate();
        BaseUniqueIDGenerator.Blueprint blueprint = BaseUniqueIDGenerator.parse(result);
        assertThat(result.length, is(8));
        assertThat(blueprint.getClusterId(), is(CLUSTER_ID));
    }

    @Test
    public void concurrentTest() throws Exception {
        final int threadCount = 20;
        final int batchSize = 500;

        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final ConcurrentMap<Integer, Deque<byte[]>> result = new ConcurrentHashMap<Integer, Deque<byte[]>>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final Integer number = 10 + i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        start.await();
                        BaseUniqueIDGenerator generator =
                                SynchronizedUniqueIDGenerator.generator(zookeeperQuorum, znode);
                        result.put(number, generator.batch(batchSize));
                    } catch (IOException e) {
                        fail();
                    } catch (InterruptedException e) {
                        fail();
                    } catch (GeneratorException e) {
                        fail();
                    }
                    done.countDown();
                }
            }, String.valueOf(number)).start();
        }

        ready.await();
        start.countDown();
        done.await();

        assertThat(result.size(), is(threadCount));

        Set<byte[]> allIDs = new HashSet<byte[]>();
        for (Map.Entry<Integer, Deque<byte[]>> entry : result.entrySet()) {
            assertThat(entry.getValue().size(), is(batchSize));
            allIDs.addAll(entry.getValue());
        }
        assertThat(allIDs.size(), is(threadCount * batchSize));
    }

    @Test
    @Ignore
    public void testAgainstRealQuorum() throws Exception {
        ZooKeeperConnection.configure("zka,zkb,zkc");
        concurrentTest();
    }

    @Test
    public void relinquishResourceClaimTest() throws Exception {
        SynchronizedUniqueIDGenerator generator = SynchronizedUniqueIDGenerator.generator(zookeeperQuorum, znode);
        generator.generate();
        int claim1 = generator.resourceClaim.hashCode();

        // Explicitly relinquish the generator ID claim.
        generator.relinquishGeneratorIDClaim();

        generator.generate();
        int claim2 = generator.resourceClaim.hashCode();

        // Verify that a new ResourceClaim object was instantiated.
        assertThat(claim1, is(not(claim2)));
    }
}

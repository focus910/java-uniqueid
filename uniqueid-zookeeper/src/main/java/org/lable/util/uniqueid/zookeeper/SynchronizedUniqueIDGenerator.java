package org.lable.util.uniqueid.zookeeper;

import org.lable.util.uniqueid.BaseUniqueIDGenerator;
import org.lable.util.uniqueid.GeneratorException;
import org.lable.util.uniqueid.zookeeper.connection.ZooKeeperConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link org.lable.util.uniqueid.BaseUniqueIDGenerator} that coordinates the generator ID used by other processes
 * using this class via the same ZooKeeper quorum by attempting to claim an available ID for itself.
 * <p/>
 * Although claims on a generator ID will be automatically relinquished after the connection to the ZooKeeper quorum
 * is lost, instances of this class should be explicitly closed after use, if you do not expect to generate anymore
 * IDs at that time.
 * <p/>
 * Because claimed generator IDs are automatically returned to the pool after a set time
 * ({@link org.lable.util.uniqueid.zookeeper.ExpiringResourceClaim#DEFAULT_TIMEOUT}),
 * there is no guarantee that IDs generated have the same generator ID.
 */
public class SynchronizedUniqueIDGenerator extends BaseUniqueIDGenerator {
    final static Logger logger = LoggerFactory.getLogger(SynchronizedUniqueIDGenerator.class);

    ResourceClaim resourceClaim;
    final int poolSize;

    static ConcurrentMap<String, SynchronizedUniqueIDGenerator> instances =
            new ConcurrentHashMap<String, SynchronizedUniqueIDGenerator>();

    /**
     * Create a new SynchronizedUniqueIDGenerator instance.
     *
     * @param resourceClaim Resource claim for a generator ID.
     * @param clusterId     Cluster ID to use (0 <= n < 16).
     */
    SynchronizedUniqueIDGenerator(ResourceClaim resourceClaim, int clusterId) {
        super(resourceClaim.get(), clusterId);
        this.poolSize = resourceClaim.poolSize;
        this.resourceClaim = resourceClaim;
    }

    /**
     * Get the synchronized ID generator instance.
     *
     * @param zookeeperQuorum Addresses of the ZooKeeper quorum to connect to.
     * @param znode Base-path of the resource pool in ZooKeeper.
     *
     * @return An instance of this class.
     * @throws IOException Thrown when something went wrong trying to find the cluster ID or trying to claim a
     *                     generator ID.
     */
    public static synchronized SynchronizedUniqueIDGenerator generator(String zookeeperQuorum, String znode)
            throws IOException {

        String instanceKey = zookeeperQuorum + "@" + znode;
        if (!instances.containsKey(instanceKey)) {
            ZooKeeperConnection.configure(zookeeperQuorum);
            int clusterId = ClusterID.get(ZooKeeperConnection.get(), znode);
            assertParameterWithinBounds("cluster-ID", 0, MAX_CLUSTER_ID, clusterId);
            logger.debug("Creating new instance.");
            int poolSize = BaseUniqueIDGenerator.MAX_GENERATOR_ID + 1;
            ResourceClaim resourceClaim = ExpiringResourceClaim.claim(ZooKeeperConnection.get(), poolSize, znode);
            instances.putIfAbsent(instanceKey, new SynchronizedUniqueIDGenerator(resourceClaim, clusterId));
        }
        return instances.get(instanceKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] generate() throws GeneratorException {
        try {
            generatorId = resourceClaim.get();
        } catch (IllegalStateException e) {
            // Claim expired?
            String znode = resourceClaim.getConfiguredZNode();
            resourceClaim.close();
            try {
                resourceClaim = ExpiringResourceClaim.claim(ZooKeeperConnection.get(), poolSize, znode);
            } catch (IOException ioe) {
                throw new GeneratorException(ioe);
            }
            generatorId = resourceClaim.get();
        }
        return super.generate();
    }

    /**
     * Return the claimed generator ID to the pool. Call this when you are done generating IDs. If you don't the
     * claim will expire automatically, but this takes a while.
     */
    public void relinquishGeneratorIDClaim() {
        resourceClaim.close();
    }
}

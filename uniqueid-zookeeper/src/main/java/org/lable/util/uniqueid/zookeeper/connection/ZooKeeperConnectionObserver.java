package org.lable.util.uniqueid.zookeeper.connection;

/**
 * Implementing classes wish to be notified of changes in the status of the connection to the ZooKeeper quorum.
 */
public interface ZooKeeperConnectionObserver {
    /**
     * Called when the connection to the ZooKeeper quorum was interrupted.
     */
    public void disconnected();

    /**
     * Called when the connection to the ZooKeeper quorum is established.
     */
    public void connected();
}

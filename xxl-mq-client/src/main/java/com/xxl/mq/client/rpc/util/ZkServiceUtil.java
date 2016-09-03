package com.xxl.mq.client.rpc.util;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * zookeeper service registry
 * @author xuxueli 2015-10-29 14:43:46
 */
public class ZkServiceUtil {
    private static final Logger logger = LoggerFactory.getLogger(ZkServiceUtil.class);

	// ------------------------------ zookeeper client ------------------------------
	private static ZooKeeper zooKeeper;
	private static ReentrantLock INSTANCE_INIT_LOCK = new ReentrantLock(true);

	private static ZooKeeper getInstance(){
		if (zooKeeper==null) {
			try {
				if (INSTANCE_INIT_LOCK.tryLock(2, TimeUnit.SECONDS)) {
					/*final CountDownLatch countDownLatch = new CountDownLatch(1);
					countDownLatch.countDown();
					countDownLatch.await();*/
					zooKeeper = new ZooKeeper(Environment.ZK_ADDRESS, 30000, new Watcher() {
						@Override
						public void process(WatchedEvent watchedEvent) {
							// session expire, close old and create new
							if (watchedEvent.getState() == Event.KeeperState.Expired) {
								try {
									zooKeeper.close();
								} catch (InterruptedException e) {
									logger.error("", e);
								}
								zooKeeper = null;
							}
							// add One-time trigger, ZooKeeper的Watcher是一次性的，用过了需要再注册
							try {
								String znodePath = watchedEvent.getPath();
								if (znodePath != null) {
									zooKeeper.exists(znodePath, true);
								}
							} catch (KeeperException e) {
								logger.error("", e);
							} catch (InterruptedException e) {
								logger.error("", e);
							}

							Event.EventType eventType = watchedEvent.getType();
							if (eventType == Event.EventType.NodeCreated) {
								String path = watchedEvent.getPath();
							} else if (eventType == Event.EventType.NodeDeleted) {
								String path = watchedEvent.getPath();

							} else if (eventType == Event.EventType.NodeDataChanged) {
								String path = watchedEvent.getPath();

							} else if (eventType == Event.EventType.NodeChildrenChanged) {
								syncLocalRegistryAddresss();
							}
						}
					});
					createWithParent(Environment.ZK_SERVICES_PATH);
					logger.info(">>>>>>>>> xxl-rpc zookeeper connnect success.");
				}
			} catch (InterruptedException e) {
				logger.error("", e);
			} catch (IOException e) {
				logger.error("", e);
			}
		}
		if (zooKeeper == null) {
			throw new NullPointerException(">>>>>>>>>>> xxl-rpc, zookeeper connect fail.");
		}
		return zooKeeper;
	}

	/**
	 * create node path with parent path (如果父节点不存在,循环创建父节点, 因为父节点不存在zookeeper会抛异常)
	 * @param path	()
	 */
	private static Stat createWithParent(String path){
		// valid
		if (path==null || path.trim().length()==0) {
			return null;
		}

		try {
			Stat stat = getInstance().exists(path, true);
			if (stat == null) {
				//  valid parent, createWithParent if not exists
				if (path.lastIndexOf("/") > 0) {
					String parentPath = path.substring(0, path.lastIndexOf("/"));
					Stat parentStat = getInstance().exists(parentPath, true);
					if (parentStat == null) {
						createWithParent(parentPath);
					}
				}
				// create desc node path
				zooKeeper.create(path, new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}
			return getInstance().exists(path, true);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return null;
	}

	// ------------------------------ register service ------------------------------

    /**
     * register service
	 * {
	 *     registry-key1:[address1, address2, address3]
	 *     registry-key2:[address1, address2, address3]
	 * }
     */
    public static void registry(int port, Set<String> registryKeys) throws KeeperException, InterruptedException {
    	// valid
    	if (port < 1 || (registryKeys==null || registryKeys.size()==0)) {
    		return;
    	}

		String address = IpUtil.getAddress(port);

		// base path
		Stat stat = getInstance().exists(Environment.ZK_SERVICES_PATH, true);
		if (stat == null) {
			getInstance().create(Environment.ZK_SERVICES_PATH, new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		}

		// register
		for (String registryKey : registryKeys) {

			// init servicePath prefix : servicePath : xxl-rpc/registryKey/address(ip01:port9999)
			String ifacePath = Environment.ZK_SERVICES_PATH.concat("/").concat(registryKey);
			String addressPath = Environment.ZK_SERVICES_PATH.concat("/").concat(registryKey).concat("/").concat(address);

			// ifacePath(parent) path must be PERSISTENT
			Stat ifacePathStat = getInstance().exists(ifacePath, true);
			if (ifacePathStat == null) {
				getInstance().create(ifacePath, new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}

			// register service path must be EPHEMERAL
			Stat addreddStat = getInstance().exists(addressPath, true);
			if (addreddStat == null) {
				String path = getInstance().create(addressPath, address.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
			}
			logger.info(">>>>>>>>>>> xxl-mq register success, registryKey:{}, address:{}, addressPath:{}", registryKey, address, addressPath);
		}

    }

	// ------------------------------ discover service ------------------------------
	private static Executor executor = Executors.newCachedThreadPool();
	static {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				while (true) {
					syncLocalRegistryAddresss();
					try {
						TimeUnit.SECONDS.sleep(60L);
					} catch (Exception e) {
						logger.error("", e);
					}
				}
			}
		});
	}


	/**
	 * 	/xxl-rpc/iface1/address1
	 * 	/xxl-rpc/iface1/address2
	 * 	/xxl-rpc/iface1/address3
	 * 	/xxl-rpc/iface2/address1
	 */
	private static volatile ConcurrentMap<String, Set<String>> registryKeyToAddresss = new ConcurrentHashMap<String, Set<String>>();

	public static void syncLocalRegistryAddresss(){
		ConcurrentMap<String, Set<String>> tempMap = new ConcurrentHashMap<String, Set<String>>();
		try {
			// iface list
			List<String> registryKeyList = getInstance().getChildren(Environment.ZK_SERVICES_PATH, true);

			if (registryKeyList!=null && registryKeyList.size()>0) {
				for (String registryKey : registryKeyList) {

					// address list
					String ifacePath = Environment.ZK_SERVICES_PATH.concat("/").concat(registryKey);
					List<String> addressList = getInstance().getChildren(ifacePath, true);

					if (addressList!=null && addressList.size() > 0) {
						Set<String> addressSet = new HashSet<String>();
						for (String address : addressList) {

							// data from address
							String addressPath = ifacePath.concat("/").concat(address);
							byte[] bytes = getInstance().getData(addressPath, false, null);
							addressSet.add(new String(bytes));
						}
						tempMap.put(registryKey, addressSet);
					}
				}
				registryKeyToAddresss = tempMap;
				logger.info(">>>>>>>>>>> xxl-mq syncLocalRegistryAddresss success: {}", registryKeyToAddresss);
			}

		} catch (Exception e) {
			logger.error("", e);
		}
	}

	public static int[] registryRankInfo(String registryKey, String address) {
		Set<String> addressSet= registryKeyToAddresss.get(registryKey);
		if (addressSet==null || !addressSet.contains(address)){
			logger.info(">>>>>>>>>>> xxl-mq, registryRank fail, registryKey={}, address={}", registryKey, address);
			return null;
		}
		int[] result = new int[2];
		result[0] = addressSet.size();

		TreeSet<String> sortSet = new TreeSet<String>(addressSet);
		int index = 0;
		for (String item: sortSet) {
			if (item.equals(address)) {
				result[1] = index;
				break;
			}
		}

		return result;
	}

	public static String discover(String registryKey) {
		Set<String> addressSet = registryKeyToAddresss.get(registryKey);
		if (addressSet==null || addressSet.size()==0) {
			return null;
		}

		String address;
		List<String> addressArr = new ArrayList<String>(addressSet);
		int size = addressSet.toArray().length;
		if (size == 1) {
			address = addressArr.get(0);
		} else {
			address = addressArr.get(new Random().nextInt(size));
		}
		return address;
	}


}
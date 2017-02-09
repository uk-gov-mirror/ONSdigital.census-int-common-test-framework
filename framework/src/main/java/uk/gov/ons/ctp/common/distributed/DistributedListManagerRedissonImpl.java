package uk.gov.ons.ctp.common.distributed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 
 * A generic distributed list (a crude map effectively) (or lists plural) of
 * things T This is a Redisson specific implementation of the
 * DistributedListManager. Using this, application code does not need to know
 * about the redisson specifics, other than obtaining the client connection.
 * 
 * @param <T> our thing type
 */
public class DistributedListManagerRedissonImpl<T> extends DistributedManagerBase implements DistributedListManager<T> {

  private static String LOCK_KEY = "lock";
  private Integer timeToWait;
  private Integer timeToLive;
  private RedissonClient redissonClient;

  /**
   * create the impl
   * 
   * @param keyRoot each list that gets saved with this impl we be stored with
   *          this prefix in its key
   * @param redissonClient the client connected to the underlying redis server
   * @param timeToLive the time that each list added will be allowed to live in
   *          seconds before the underlying redis server purges it
   */
  public DistributedListManagerRedissonImpl(String keyRoot, RedissonClient redissonClient, Integer timeToWait,
      Integer timeToLive) {
    super(keyRoot);
    this.timeToWait = timeToWait;
    this.timeToLive = timeToLive;
    this.redissonClient = redissonClient;
  }

  @Override
  public void saveList(String key, List<T> list, boolean unlock) throws LockingException {
    if (!containerIsLockedByCurrentThread()) {
      String msg = String.format("DistributedList lock must be held by current thread before saving");
      throw new LockingException(msg);
    }
    RBucket<List<T>> bucket = redissonClient.getBucket(createKey(key));
    bucket.set(list, timeToLive, TimeUnit.SECONDS);
    if (unlock) {
      unlockContainer();
    }
  }

  @Override
  public List<T> findList(String key, boolean unlock) throws LockingException {
    if (!containerIsLockedByCurrentThread()) {
      lockContainer();
    }
    RKeys keys = redissonClient.getKeys();
    Iterable<String> matches = keys.getKeysByPattern(createAllInstancesKey(key));
    List<T> allContents = new ArrayList<>();
    for (String match : matches) {
      RBucket<List<T>> bucket = redissonClient.getBucket(match);
      if (bucket != null) {
        List<T> bucketContents = bucket.get();
        if (bucketContents != null) {
          allContents.addAll(bucketContents);
        }
      }
    }
    if (unlock) {
      unlockContainer();
    }
    return allContents;
  }

  @Override
  public void deleteList(String key, boolean unlock) throws LockingException {
    if (!containerIsLockedByCurrentThread()) {
      lockContainer();
    }
    RBucket<List<T>> bucket = redissonClient.getBucket(createKey(key));
    bucket.delete();
    if (unlock) {
      unlockContainer();
    }
  }

  @Override
  public void lockContainer() throws LockingException {
    boolean locked = false;
    String lockName = createGlobalKey(LOCK_KEY);
    RLock lock = redissonClient.getFairLock(lockName);
    if (lock != null) {
      try {
        locked = lock.tryLock(timeToWait, timeToLive, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // locked still false...
      }
    }
    if (!locked) {
      String msg = String.format("Failed to obtain lock {}", lockName);
      throw new LockingException(msg);
    }
  }

  @Override
  public boolean containerIsLockedByCurrentThread() {
    String lockName = createGlobalKey(LOCK_KEY);
    RLock lock = redissonClient.getFairLock(lockName);
    return lock.isHeldByCurrentThread();
  }

  @Override
  public void unlockContainer() throws LockingException {
    boolean unlocked = false;
    String lockName = createGlobalKey(LOCK_KEY);
    RLock lock = redissonClient.getFairLock(lockName);
    if (lock != null && lock.isHeldByCurrentThread()) {
      lock.unlock();
      unlocked = true;
    }
    if (!unlocked) {
      String msg = String.format("Failed to unlock {}", lockName);
      throw new LockingException(msg);
    }
  }
}

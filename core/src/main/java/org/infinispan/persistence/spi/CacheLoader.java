package org.infinispan.persistence.spi;

import net.jcip.annotations.ThreadSafe;
import org.infinispan.lifecycle.Lifecycle;

/**
 * Defines the logic for loading data from an external storage. The writing of data is optional and coordinated through
 * a {@link CacheWriter}.
 *
 * @author Mircea Markus
 * @since 6.0
 */
@ThreadSafe
public interface CacheLoader<K,V> extends Lifecycle {

   /**
    * Used to initialize a cache loader.  Typically invoked by the {@link org.infinispan.persistence.manager.PersistenceManager}
    * when setting up cache loaders.
    */
   void init(InitializationContext ctx);

   /**
    * Fetches an entry from the storage.
    * @return the entry, or null if the entry does not exist.
    */
   MarshalledEntry<K,V> load(K key);

   /**
    * Returns true if the storage contains an entry associated with the given key.
    */
   boolean contains(K key);
}

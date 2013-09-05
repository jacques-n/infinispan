package org.infinispan.loaders.jdbc.stringbased;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.persistence.CacheLoaderException;
import org.infinispan.persistence.InitializationContextImpl;
import org.infinispan.loaders.jdbc.ManagedConnectionFactoryTest;
import org.infinispan.loaders.jdbc.configuration.JdbcStringBasedStoreConfiguration;
import org.infinispan.loaders.jdbc.configuration.JdbcStringBasedStoreConfigurationBuilder;
import org.infinispan.loaders.jdbc.connectionfactory.ManagedConnectionFactory;
import org.infinispan.persistence.keymappers.UnsupportedKeyTypeException;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.manager.CacheContainer;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.test.fwk.UnitTestDatabaseManager;
import org.infinispan.util.DefaultTimeService;
import org.testng.annotations.Test;

/**
 * @author Mircea.Markus@jboss.com
 */
@Test (groups = "functional", testName = "loaders.jdbc.stringbased.StringStoreWithManagedConnectionTest")
public class StringStoreWithManagedConnectionTest extends ManagedConnectionFactoryTest {

   @Override
   protected AdvancedLoadWriteStore createStore() throws Exception {
      JdbcStringBasedStoreConfigurationBuilder storeBuilder = TestCacheManagerFactory
            .getDefaultCacheConfiguration(false)
            .persistence()
               .addStore(JdbcStringBasedStoreConfigurationBuilder.class);

      storeBuilder.dataSource()
            .jndiUrl(getDatasourceLocation());
      UnitTestDatabaseManager.buildTableManipulation(storeBuilder.table(), false);

      JdbcStringBasedStore stringBasedCacheStore = new JdbcStringBasedStore();
      stringBasedCacheStore.init(new InitializationContextImpl(storeBuilder.create(), getCache(), getMarshaller(), new DefaultTimeService()));
      stringBasedCacheStore.start();
      return stringBasedCacheStore;
   }

   public void testLoadFromFile() throws Exception {
      CacheContainer cm = null;
      try {
         cm = TestCacheManagerFactory.fromXml("configs/managed/str-managed-connection-factory.xml");
         Cache<String, String> first = cm.getCache("first");
         Cache<String, String> second = cm.getCache("second");

         StoreConfiguration firstCacheLoaderConfig = first.getCacheConfiguration().persistence().stores().get(0);
         assert firstCacheLoaderConfig != null;
         StoreConfiguration secondCacheLoaderConfig = second.getCacheConfiguration().persistence().stores().get(0);
         assert secondCacheLoaderConfig != null;
         assert firstCacheLoaderConfig instanceof JdbcStringBasedStoreConfiguration;
         assert secondCacheLoaderConfig instanceof JdbcStringBasedStoreConfiguration;
         JdbcStringBasedStore loader = (JdbcStringBasedStore) TestingUtil.getFirstLoader(first);
         assert loader.getConnectionFactory() instanceof ManagedConnectionFactory;
      } finally {
         TestingUtil.killCacheManagers(cm);
      }
   }

   @Override
   public String getDatasourceLocation() {
      return "java:/StringStoreWithManagedConnectionTest/DS";
   }

   @Override
   @Test(expectedExceptions = UnsupportedKeyTypeException.class)
   public void testLoadAndStoreMarshalledValues() throws CacheLoaderException {
      super.testLoadAndStoreMarshalledValues();
   }

}

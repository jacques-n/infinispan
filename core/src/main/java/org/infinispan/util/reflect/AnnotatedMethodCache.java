/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.util.reflect;

import org.infinispan.factories.annotations.DefaultFactoryFor;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.MethodInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This helper class encapsulates reading the Jandex annotation indexes built at compile-time, loading these into
 * memory, and making these available for any service which would otherwise have to resort to reflection.
 *
 * @author Manik Surtani
 * @since 5.1
 */
public class AnnotatedMethodCache {
   /**
    * Contains class definitions of component factories that can be used to construct certain components
    */
   private static final Map<String, String[]> FACTORIES = new HashMap<String, String[]>();

   private static final Map<String, List<CachedMethod>> INJECT_METHODS = new HashMap<String, List<CachedMethod>>();
   private static final Map<String, List<CachedMethod>> START_METHODS = new HashMap<String, List<CachedMethod>>();
   private static final Map<String, List<CachedMethod>> STOP_METHODS = new HashMap<String, List<CachedMethod>>();
   private static final Map<String, List<CachedMethod>> INJECT_METHODS_INC_SUB = new ConcurrentHashMap<String, List<CachedMethod>>();
   private static final Map<String, List<CachedMethod>> START_METHODS_INC_SUB = new ConcurrentHashMap<String, List<CachedMethod>>();
   private static final Map<String, List<CachedMethod>> STOP_METHODS_INC_SUB = new ConcurrentHashMap<String, List<CachedMethod>>();

   private static final Log log = LogFactory.getLog(AnnotatedMethodCache.class);

   static {
      final DotName INJECT_ANNOTATION = DotName.createSimple(Inject.class.getName());
      final DotName START_ANNOTATION = DotName.createSimple(Start.class.getName());
      final DotName STOP_ANNOTATION = DotName.createSimple(Stop.class.getName());
      final DotName FACTORY_FOR_ANNOTATION = DotName.createSimple(DefaultFactoryFor.class.getName());

      try {
         ClassLoader classLoader = AnnotatedMethodCache.class.getClassLoader();
         // infinispan-core-jandex.idx is generated by the build process and should exist in the root of infinispan-core.jar
         InputStream indexStream = classLoader.getResourceAsStream("infinispan-core-jandex.idx");
         Index annotationIndex = new IndexReader(indexStream).read();

         // Scan for factories
         for (AnnotationInstance ai : annotationIndex.getAnnotations(FACTORY_FOR_ANNOTATION)) {
            if (ai.target() instanceof ClassInfo) {
               ClassInfo ci = (ClassInfo) ai.target();
               FACTORIES.put(ci.name().toString(), ai.value("classes").asStringArray());
            }
         }

         // Scan for Inject methods
         collectAndCacheMethodAnnotations(annotationIndex, classLoader, INJECT_ANNOTATION, INJECT_METHODS);

         // Scan for Start methods
         collectAndCacheMethodAnnotations(annotationIndex, classLoader, START_ANNOTATION, START_METHODS);

         // Scan for Stop methods
         collectAndCacheMethodAnnotations(annotationIndex, classLoader, STOP_ANNOTATION, STOP_METHODS);

      } catch (IOException e) {
         log.fatal("Cannot load annotation index!", e);
      }
   }

   private static boolean match(Class<?> clazz, String className, ClassLoader classLoader) {
      try {
         return (classLoader.loadClass(className).isAssignableFrom(clazz));
      } catch (ClassNotFoundException e) {
         log.warn("Cannot load class " + className, e);
         return false;
      }
   }

   private static void collectAndCacheMethodAnnotations(Index annotationIndex, ClassLoader classLoader,
         DotName annotationType, Map<String, List<CachedMethod>> targetCache) {
      for (AnnotationInstance ai : annotationIndex.getAnnotations(annotationType)) {
         if (ai.target() instanceof MethodInfo) {
            MethodInfo methodInfo = (MethodInfo) ai.target();
            ClassInfo classInfo = methodInfo.declaringClass();
            String clazz = classInfo.name().toString();
            List<CachedMethod> methodList = targetCache.get(clazz);
            if (methodList == null) {
               methodList = new LinkedList<CachedMethod>();
               targetCache.put(clazz, methodList);
            }
            try {
               methodList.add(new CachedMethod(ai, classLoader, methodInfo));
            } catch (Exception e) {
               log.fatal("Caught exception scanning annotations on " + methodInfo, e);
            }
         }
      }
   }

   public static List<CachedMethod> getInjectMethods(Class<?> clazz, ClassLoader classLoader) {
      return getCachedMethods(clazz, classLoader, INJECT_METHODS, INJECT_METHODS_INC_SUB);
   }

   public static List<CachedMethod> getStartMethods(Class<?> clazz, ClassLoader classLoader) {
      return getCachedMethods(clazz, classLoader, START_METHODS, START_METHODS_INC_SUB);
   }

   public static List<CachedMethod> getStopMethods(Class<?> clazz, ClassLoader classLoader) {
      return getCachedMethods(clazz, classLoader, STOP_METHODS, STOP_METHODS_INC_SUB);
   }

   private static List<CachedMethod> getCachedMethods(Class<?> clazz, ClassLoader classLoader,
                                                      Map<String, List<CachedMethod>> basicMethodCache,
                                                      Map<String, List<CachedMethod>> richMethodCache) {
      String className = clazz.getName();
      List<CachedMethod> l = richMethodCache.get(className);
      if (l == null) {
         synchronized (AnnotatedMethodCache.class) {
            l = richMethodCache.get(className);
            if (l == null) {

               l = basicMethodCache.get(className);
               if (l != null) {
                  // check superclasses/interfaces
                  for (Map.Entry<String, List<CachedMethod>> e : basicMethodCache.entrySet()) {
                     if (match(clazz, e.getKey(), classLoader)) l.addAll(e.getValue());
                  }
               } else {
                  l = new LinkedList<CachedMethod>();
                  for (Map.Entry<String, List<CachedMethod>> e : basicMethodCache.entrySet()) {
                     if (match(clazz, e.getKey(), classLoader)) l.addAll(e.getValue());
                  }
                  if (l.isEmpty()) l = Collections.emptyList();
               }
               richMethodCache.put(className, l);
            }
         }
      }
      return l;
   }

   public static Map<String, String[]> getDefaultFactories() {
      return FACTORIES;
   }
}
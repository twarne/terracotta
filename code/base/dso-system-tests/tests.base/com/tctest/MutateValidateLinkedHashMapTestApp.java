/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.util.Assert;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Random;
import java.util.Iterator;

public class MutateValidateLinkedHashMapTestApp extends AbstractMutateValidateTransparentApp {

  private int          upbound = 1000;
  private final String appId;
  private final MapRoot map1 = new MapRoot(upbound);
  private final MapRoot map2 = new MapRoot(upbound+100);
  final static EventNode eventIndex = new EventNode(0, "test");

  public MutateValidateLinkedHashMapTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
    this.appId = appId;
  }

  protected void mutate() throws Throwable {
    EventNode event = null;
    Random r = new Random();
    boolean empty;
    
    while(true) {
      // if empty then do put
      synchronized(map1) {
        empty = map1.getMap().isEmpty();
      }
      
      if(empty || r.nextBoolean()) {
        synchronized(map1) {
          event = EventNode.produce();
          map1.getMap().put(new Integer(event.getId()),event);
          debugPrintln("*** Add item "+event.getId());
        }
      } else {
        // move first item from set1 to set2
        synchronized(map1) {
          Set s = map1.getMap().keySet();
          Iterator it = s.iterator();
          if(it.hasNext()) {
            Integer key = (Integer) it.next();
            event = (EventNode) map1.getMap().remove(key);
            map2.getMap().put(new Integer(event.getId()), event);
            debugPrintln("*** Move item "+event.getId());
            
            if(event.getId() >= upbound) break;
          }    
          // debugPrintln("*** map1 size="+map1.getMap().size()+ "  map2 size=" + map2.getMap().size());
        }
      }
      Thread.sleep(r.nextInt(10));
    }   
    debugPrintln("*** Done with mutation");
  }

  protected void validate() throws Throwable {
    Random r = new Random();
    int id = 0;
    
    debugPrintln("*** Start verification");
    // check size
    synchronized(map1) {
       int mapSize = map1.getMap().size() + map2.getMap().size();
      if( eventIndex.getId() != mapSize) {
        notifyError("*** Expected total events "+ eventIndex.getId() + " but got " + mapSize);
      }
      Assert.assertTrue(eventIndex.getId() == mapSize);
    }
    
    while(id < eventIndex.getId()) {
      synchronized(map1) {
        Integer key = new Integer (id);
        boolean inMap1 = map1.getMap().containsKey(key);
        boolean inMap2 = map2.getMap().containsKey(key);
        if((inMap1 && inMap2) || (!inMap1 && !inMap2) ) {
          notifyError("Item " + id + (inMap1? " in both maps" : " not found"));
        }
        Assert.assertTrue((inMap1 && !inMap2) || (!inMap1 && inMap2));
        id++;
      }
      // debugPrintln("*** Verify id=" + id);
      Thread.sleep(r.nextInt(3));
    }   
    System.out.println("*** Verification Successful");    
  }

  public static void visitL1DSOConfig(ConfigVisitor visitor, DSOClientConfigHelper config) {
    String testClass = MutateValidateLinkedHashMapTestApp.class.getName();
    TransparencyClassSpec spec = config.getOrCreateSpec(testClass);
    config.addIncludePattern(EventNode.class.getName());
    config.addIncludePattern(MapRoot.class.getName());

    String methodExpression = "* " + testClass + "*.*(..)";
    config.addWriteAutolock(methodExpression);
    spec.addRoot("map1", "map1");
    spec.addRoot("map2", "map2");
    spec.addRoot("eventIndex", "eventIndex");
  }
  
  private static class MapRoot {
    private final LinkedHashMap map;
    
    public MapRoot(int cap) {
      map = new LinkedHashMap(cap);
    }
    
    public LinkedHashMap getMap() {
      return(map);
    }
    
    public void clear() {
      map.clear();
    }
  }
  
  private static class EventNode {
    String name;
    int id;
    
    public static EventNode produce() {
      EventNode node;
      synchronized(eventIndex) {
        node = new EventNode(eventIndex.getId(), "Event" + eventIndex.getId() );
        eventIndex.setId(eventIndex.getId() + 1);
      }
      // System.out.println("*** Produce id=" + node.getId());
      return(node);
    }
    
    public EventNode(int id, String name) {
      this.id = id;
      this.name = name;
    }
        
    public int getId() {
      return(id);
    }
    
    public void setId(int id) {
      this.id = id;
    }
  }

}

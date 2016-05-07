package freenet.node.events;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Ideas:
 *  - Use separate event dispatchers for messages, system events etc.
 *  - Use nested classes as event listeners.
 *
 * Possible problems:
 *  - Type safety quite low
 *  - Complex processes might require chains of event publishing, processing, publishing etc.
 *  - Performance penalties
 */

public class EventDispatcher {

    public enum Event {
        MSG_FNPDetectedIPAddress,
        MSG_FNPOpennetAnnounceRequest;
    }
   
    private HashMap<Event, ArrayList<EventListener>> subscriptions;
    
    public EventDispatcher() {
        subscriptions = new HashMap<Event, ArrayList<EventListener>>();
    }
    
    /**
     * 
     * @param event The event to be subscribed.
     * @param listener The object to be notified in case the event occurs.
     */
    public void subscribe(Event event, EventListener listener) {
        
        System.out.println("Subscribing object of type " + listener.getClass() + " to event " + event);
       
        if (!subscriptions.containsKey(event)) {
            subscriptions.put(event, new ArrayList<EventListener>());
        
        } else if (subscriptions.get(event).indexOf(listener) != -1) {
            throw new RuntimeException("Redundant subscription of listener!");
        }
        
        subscriptions.get(event).add(listener);
    }
    
    /**
     * 
     * @param event The event for which no notification shall be done in the future.
     * @param listener The object that shall be removed from the notification list for the given event.
     */
    public void unsubscribe(Event event, EventListener listener) {
        subscriptions.get(event).remove(listener);
    }
    
    /**
     * 
     * @param event The event that occurred.
     * @param data The data associated with the event.
     */
    public void publish(Event event, Object data) {
        
        System.out.println("Publishing event " + event);
        for (EventListener l : subscriptions.get(event)) {
            l.handleEvent(event, data);
            System.out.println("... " + data.getClass());
        }
    }
    
    public void unsubscribeAll() {
        for (ArrayList<EventListener> l : subscriptions.values()) {
            l.clear();
        }
        subscriptions.clear();
    }
}

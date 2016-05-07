package freenet.node.events;

import freenet.node.events.EventDispatcher.Event;

public interface EventListener {
    public void handleEvent (Event event, Object data);
}

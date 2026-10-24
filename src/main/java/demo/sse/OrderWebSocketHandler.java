package demo.sse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Keeps browser WebSocket sessions and broadcasts the same order changes as SSE. */
@Component
public class OrderWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectProvider<OrderController> orders;

    public OrderWebSocketHandler(ObjectProvider<OrderController> orders) { this.orders = orders; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.put(session.getId(), session);
        session.sendMessage(new TextMessage(orders.getObject().orderMessage("connected")));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    public int connectionCount() { return sessions.size(); }

    public void broadcast(String message) {
        sessions.forEach((id, session) -> {
            if (!session.isOpen()) { sessions.remove(id); return; }
            try { session.sendMessage(new TextMessage(message)); }
            catch (IOException error) { sessions.remove(id); }
        });
    }
}

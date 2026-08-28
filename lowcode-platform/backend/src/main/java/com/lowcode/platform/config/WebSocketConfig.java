package com.lowcode.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic"); // /topic/runs/{runId}
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // БЕЗ .withSockJS(): фронтенд (executionSocket.ts) подключается через
        // stompjs Client с brokerURL — это сырой WebSocket, а не SockJS-протокол
        // (SockJS ждёт свой handshake и путь вида /ws/runs/{server}/{session}/websocket,
        // а не прямое WS-подключение к голому /ws/runs). Раньше эти два конца
        // говорили на разных протоколах поверх одного URL — отсюда
        // "WebSocket connection failed" при первом же реальном прогоне
        // фронт+бэк вместе. SockJS существует ради fallback для браузеров без
        // нативного WebSocket — для этого инструмента не нужен.
        registry.addEndpoint("/ws/runs").setAllowedOriginPatterns("*");
        // co-editing канал /ws/schemes/{schemeId}/collab — отдельный relay
        // на Yjs binary protocol, регистрируется вне STOMP (см. README)
    }
}

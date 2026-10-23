package com.matheus.orderflow.messaging.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O Kafka entrega mensagens "pelo menos uma vez", então o mesmo evento pode chegar duplicado.
 * Este serviço registra os eventos já processados por cada consumer, na mesma transação do processamento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * @return true se for a primeira vez que o consumer recebe o evento; false se for duplicado.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markAsProcessed(UUID eventId, String consumer) {
        var id = new ProcessedEventId(eventId, consumer);
        if (processedEventRepository.existsById(id)) {
            log.info("Evento duplicado ignorado: {} (consumer {})", eventId, consumer);
            return false;
        }
        processedEventRepository.save(new ProcessedEvent(id));
        return true;
    }
}

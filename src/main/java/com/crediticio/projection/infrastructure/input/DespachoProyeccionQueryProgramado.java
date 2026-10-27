package com.crediticio.projection.infrastructure.input;

import com.crediticio.config.SagaRecomendacionesProperties;
import com.crediticio.projection.application.DespacharProyeccionQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ejecuta la entrega durable de altas y estados a Query cuando está habilitada la integración S1. */
@Component
@ConditionalOnProperty(prefix = "scoring.saga", name = "habilitada", havingValue = "true")
public class DespachoProyeccionQueryProgramado {
    private static final Logger log = LoggerFactory.getLogger(DespachoProyeccionQueryProgramado.class);
    private final DespacharProyeccionQueryService servicio;
    private final int maxEventos;

    public DespachoProyeccionQueryProgramado(DespacharProyeccionQueryService servicio,
            SagaRecomendacionesProperties propiedades) {
        this.servicio = servicio;
        this.maxEventos = propiedades.trabajosPorCiclo();
    }

    @Scheduled(fixedDelayString = "${scoring.saga.frecuencia-reenvio:PT5S}",
            initialDelayString = "${scoring.saga.frecuencia-reenvio:PT5S}")
    public void ejecutar() {
        try {
            servicio.ejecutarCiclo(maxEventos);
        } catch (RuntimeException ex) {
            log.error("Falló un ciclo de entrega del outbox de proyección de Query", ex);
        }
    }
}

package com.crediticio.projection.application;

import com.crediticio.evaluations.domain.PoliticaReintentos;
import com.crediticio.projection.domain.EventoProyeccionQuery;
import com.crediticio.projection.ports.output.OutboxProyeccionQueryPort;
import com.crediticio.projection.ports.output.PublicarProyeccionQueryPort;
import com.crediticio.projection.ports.output.ResultadoEntregaProyeccionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.UUID;

/** Caso de uso del outbox: reclama en transacción breve, publica fuera de ella y registra el resultado. */
@Service
@ConditionalOnProperty(prefix = "scoring.saga", name = "habilitada", havingValue = "true")
public class DespacharProyeccionQueryService {
    private static final Logger log = LoggerFactory.getLogger(DespacharProyeccionQueryService.class);
    private final OutboxProyeccionQueryPort outbox;
    private final PublicarProyeccionQueryPort publicador;
    private final PoliticaReintentos politica;

    public DespacharProyeccionQueryService(OutboxProyeccionQueryPort outbox,
            PublicarProyeccionQueryPort publicador, PoliticaReintentos politica) {
        this.outbox = outbox;
        this.publicador = publicador;
        this.politica = politica;
    }

    /** Procesa hasta un máximo acotado por ciclo para no monopolizar el hilo programado. */
    public int ejecutarCiclo(int maxEventos) {
        int procesados = 0;
        while (procesados < maxEventos) {
            UUID concesion = UUID.randomUUID();
            var opcional = outbox.reclamarSiguiente(politica.getMaxIntentos(), concesion, politica.getConcesion());
            if (opcional.isEmpty()) return procesados;
            EventoProyeccionQuery evento = opcional.get();
            ResultadoEntregaProyeccionQuery resultado = publicador.publicar(evento);
            switch (resultado) {
                case ResultadoEntregaProyeccionQuery.Exito exito -> {
                    if (outbox.registrarEntregado(evento.idEvento(), concesion)) {
                        log.info("Evento {} {} del agregado {} entregado a Query", evento.idEvento(), evento.tipo(),
                                evento.idAgregado());
                    } else {
                        log.warn("Evento {}: la concesión expiró antes de registrar la entrega", evento.idEvento());
                    }
                }
                case ResultadoEntregaProyeccionQuery.ErrorTransitorio error -> {
                    if (politica.admiteOtroIntento(evento.intentos())) {
                        outbox.registrarErrorTransitorio(evento.idEvento(), concesion,
                                politica.esperaTras(evento.intentos()), error.codigo(), error.detalle());
                        log.warn("Evento {}: error transitorio al sincronizar Query ({}) en intento {}",
                                evento.idEvento(), error.codigo(), evento.intentos());
                    } else {
                        outbox.registrarErrorPermanente(evento.idEvento(), concesion, "REINTENTOS_AGOTADOS",
                                error.codigo() + ": " + error.detalle());
                        log.error("Evento {} agotó sus {} intentos ({}) y requiere intervención", evento.idEvento(),
                                evento.intentos(), error.codigo());
                    }
                }
                case ResultadoEntregaProyeccionQuery.ErrorPermanente error -> {
                    outbox.registrarErrorPermanente(evento.idEvento(), concesion, error.codigo(), error.detalle());
                    log.error("Evento {} requiere intervención por error permanente ({})", evento.idEvento(),
                            error.codigo());
                }
            }
            procesados++;
        }
        return procesados;
    }
}

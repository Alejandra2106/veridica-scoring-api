package com.crediticio.projection.ports.output;

import com.crediticio.projection.domain.EventoProyeccionQuery;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Persistencia del ciclo de entrega outbox, sin exponer SQL al caso de uso. */
public interface OutboxProyeccionQueryPort {
    Optional<EventoProyeccionQuery> reclamarSiguiente(int maxIntentos, UUID concesion, Duration duracionConcesion);

    boolean registrarEntregado(long idEvento, UUID concesion);

    boolean registrarErrorTransitorio(long idEvento, UUID concesion, Duration espera, String codigo, String detalle);

    boolean registrarErrorPermanente(long idEvento, UUID concesion, String codigo, String detalle);
}

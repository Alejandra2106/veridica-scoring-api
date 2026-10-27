package com.crediticio.projection.ports.output;

import com.crediticio.projection.domain.EventoProyeccionQuery;

/** Puerto HTTP hacia las operaciones internas idempotentes de evaluation-query-api. */
public interface PublicarProyeccionQueryPort {
    ResultadoEntregaProyeccionQuery publicar(EventoProyeccionQuery evento);
}

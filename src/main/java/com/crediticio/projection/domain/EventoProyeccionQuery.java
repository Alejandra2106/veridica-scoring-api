package com.crediticio.projection.domain;

import java.util.UUID;

/** Evento mínimo y durable que Scoring debe entregar a la proyección de lectura de Query. */
public record EventoProyeccionQuery(long idEvento, Tipo tipo, long idAgregado, int intentos, UUID concesion) {
    public enum Tipo {
        SOLICITANTE_PROYECTAR,
        EVALUACION_COMPLETADA
    }
}
